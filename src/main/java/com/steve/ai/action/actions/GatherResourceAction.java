package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Surface gathering action — walks to resources on or near the surface and collects them.
 *
 * <p>This action handles all resources that exist on the surface world:
 * logs/trees, plants, flowers, sand, stone, animals, and water items.
 * It uses navigation (NOT teleportation) to walk naturally to targets.</p>
 *
 * <p>For ores and deep underground blocks, use MineBlockAction instead.</p>
 */
public class GatherResourceAction extends BaseAction {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int SEARCH_RADIUS = 32;       // Surface needs wider area than tunneling
    private static final int MAX_TICKS = 6000;          // 5-minute timeout (6000 ticks)
    private static final int MINING_DELAY = 8;          // Ticks between break attempts
    private static final int REACH_DISTANCE = 5;        // Blocks: must be within this range to break
    private static final int NAVIGATION_TIMEOUT = 100;  // Ticks before we retry pathfinding

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private String resourceName;   // Resolved Minecraft block/item name
    private int quantity;
    private int gathered;
    private int ticksRunning;
    private int ticksSinceLastAction;
    private int ticksSinceNavStart;

    // Current target (block or entity)
    private BlockPos targetBlock;
    private LivingEntity targetEntity;
    private boolean isAnimalTarget;   // Whether we're hunting animals
    private boolean navigating;       // True while walking to target

    // For tree felling: stack of log positions above the first one
    private final List<BlockPos> trunkQueue = new ArrayList<>();

    // Resource name aliases → canonical Minecraft block name
    private static final Map<String, String> RESOURCE_ALIASES = new HashMap<>() {{
        // Wood shortcuts
        put("wood",        "oak_log");
        put("log",         "oak_log");
        put("logs",        "oak_log");
        put("tree",        "oak_log");
        put("trees",       "oak_log");
        put("oak",         "oak_log");
        put("birch",       "birch_log");
        put("spruce",      "spruce_log");
        put("jungle",      "jungle_log");
        put("acacia",      "acacia_log");
        put("dark_oak",    "dark_oak_log");
        put("cherry",      "cherry_log");
        put("mangrove",    "mangrove_log");
        // Surface stone aliases
        put("stone",       "stone");
        put("cobblestone", "cobblestone");
        put("andesite",    "andesite");
        put("diorite",     "diorite");
        put("granite",     "granite");
        // Surface blocks
        put("sand",        "sand");
        put("dirt",        "dirt");
        put("gravel",      "gravel");
        put("clay",        "clay");
        put("snow",        "snow_block");
        put("grass",       "grass_block");
        // Plants
        put("wheat",       "wheat");
        put("carrot",      "carrots");
        put("carrots",     "carrots");
        put("potato",      "potatoes");
        put("potatoes",    "potatoes");
        put("beetroot",    "beetroots");
        put("pumpkin",     "pumpkin");
        put("melon",       "melon");
        put("bamboo",      "bamboo");
        put("cactus",      "cactus");
        put("sugar_cane",  "sugar_cane");
        put("sugarcane",   "sugar_cane");
        // Flowers
        put("flower",      "dandelion");
        put("flowers",     "dandelion");
        put("dandelion",   "dandelion");
        put("poppy",       "poppy");
        // Mushrooms
        put("mushroom",    "red_mushroom");
        put("red_mushroom","red_mushroom");
        put("brown_mushroom","brown_mushroom");
        // Animal drops → trigger animal hunt
        put("food",        "beef");
        put("beef",        "beef");
        put("porkchop",    "porkchop");
        put("mutton",      "mutton");
        put("chicken",     "chicken");
        put("leather",     "leather");
        put("wool",        "wool");
        put("feather",     "feather");
        put("rabbit",      "rabbit");
    }};

    // Animal drop → which animal to hunt
    private static final Map<String, String> DROP_TO_ANIMAL = new HashMap<>() {{
        put("beef",     "cow");
        put("leather",  "cow");
        put("porkchop", "pig");
        put("mutton",   "sheep");
        put("wool",     "sheep");
        put("chicken",  "chicken");
        put("feather",  "chicken");
        put("rabbit",   "rabbit");
    }};

    private static final java.util.Set<String> ANIMAL_DROPS = DROP_TO_ANIMAL.keySet();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    // -----------------------------------------------------------------------
    // BaseAction implementation
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        String rawResource = task.getStringParameter("resource");
        quantity = task.getIntParameter("quantity", 1);
        gathered = 0;
        ticksRunning = 0;
        ticksSinceLastAction = 0;
        ticksSinceNavStart = 0;
        targetBlock = null;
        targetEntity = null;
        navigating = false;
        trunkQueue.clear();

        if (rawResource == null || rawResource.isBlank()) {
            result = ActionResult.failure("No resource specified for gather action");
            return;
        }

        resourceName = resolveResourceName(rawResource);
        isAnimalTarget = ANIMAL_DROPS.contains(resourceName);

        SteveMod.LOGGER.info("Steve '{}' starting gather: {} x{} (resolved from '{}')",
            steve.getSteveName(), resourceName, quantity, rawResource);

        // Make sure we're not flying (we walk, not fly)
        steve.setFlying(false);
        steve.setSprinting(false);

        // Find initial target
        if (isAnimalTarget) {
            findAnimalTarget();
        } else {
            findBlockTarget();
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastAction++;

        // Global timeout
        if (ticksRunning > MAX_TICKS) {
            steve.getNavigation().stop();
            result = ActionResult.failure(
                "Gather timeout — only found " + gathered + "/" + quantity + " " + resourceName, false);
            return;
        }

        if (isAnimalTarget) {
            tickAnimalGather();
        } else {
            tickBlockGather();
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setSprinting(false);
        trunkQueue.clear();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceName + " (" + gathered + " collected)";
    }

    // -----------------------------------------------------------------------
    // Block gathering logic
    // -----------------------------------------------------------------------

    private void tickBlockGather() {
        // First drain the trunk queue (break logs above the one we just mined)
        if (!trunkQueue.isEmpty()) {
            if (ticksSinceLastAction >= MINING_DELAY) {
                BlockPos nextLog = trunkQueue.remove(0);
                Block expectedBlock = resolveBlock(resourceName);
                if (expectedBlock != null && steve.level().getBlockState(nextLog).getBlock() == expectedBlock) {
                    breakBlock(nextLog);
                    gathered++;
                    ticksSinceLastAction = 0;
                    SteveMod.LOGGER.info("Steve '{}' felled trunk log at {} ({}/{})",
                        steve.getSteveName(), nextLog, gathered, quantity);
                    if (gathered >= quantity) {
                        steve.getNavigation().stop();
                        result = ActionResult.success("Gathered " + gathered + " " + resourceName);
                    }
                }
            }
            return;
        }

        // If no current target, search for one
        if (targetBlock == null) {
            findBlockTarget();
            if (targetBlock == null) {
                // Nothing found nearby — keep searching (don't fail immediately)
                if (ticksRunning % 60 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' searching for {} ...", steve.getSteveName(), resourceName);
                }
                return;
            }
        }

        // Check if the target block still exists
        Block expectedBlock = resolveBlock(resourceName);
        if (expectedBlock == null) {
            result = ActionResult.failure("Unknown block: " + resourceName);
            return;
        }
        if (steve.level().getBlockState(targetBlock).getBlock() != expectedBlock) {
            // Block was removed or changed — find another
            targetBlock = null;
            navigating = false;
            return;
        }

        double distToTarget = steve.blockPosition().distSqr(targetBlock);

        // If we're close enough, break the block
        if (distToTarget <= REACH_DISTANCE * REACH_DISTANCE) {
            if (ticksSinceLastAction >= MINING_DELAY) {
                breakBlock(targetBlock);
                gathered++;
                ticksSinceLastAction = 0;
                SteveMod.LOGGER.info("Steve '{}' gathered {} at {} ({}/{})",
                    steve.getSteveName(), resourceName, targetBlock, gathered, quantity);

                // For log blocks: scan upward for the rest of the trunk
                if (isLogBlock(resourceName)) {
                    scanTrunkAbove(targetBlock, expectedBlock);
                }

                targetBlock = null;
                navigating = false;

                if (gathered >= quantity) {
                    steve.getNavigation().stop();
                    result = ActionResult.success("Gathered " + gathered + " " + resourceName);
                }
            }
            // Already close — no need to walk further
            return;
        }

        // Start/continue navigation toward target
        if (!navigating) {
            steve.getNavigation().moveTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, 1.0);
            navigating = true;
            ticksSinceNavStart = 0;
            SteveMod.LOGGER.info("Steve '{}' walking to {} at {}", steve.getSteveName(), resourceName, targetBlock);
        } else {
            ticksSinceNavStart++;
            // If navigation finished but we're still far, retry
            if (steve.getNavigation().isDone() && distToTarget > REACH_DISTANCE * REACH_DISTANCE) {
                if (ticksSinceNavStart > NAVIGATION_TIMEOUT) {
                    // Stuck — try a different target
                    SteveMod.LOGGER.info("Steve '{}' stuck navigating to {}, searching for another",
                        steve.getSteveName(), targetBlock);
                    targetBlock = null;
                    navigating = false;
                } else {
                    // Retry navigation
                    steve.getNavigation().moveTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, 1.0);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Animal gathering logic
    // -----------------------------------------------------------------------

    private void tickAnimalGather() {
        // If no target entity, search
        if (targetEntity == null || !targetEntity.isAlive() || targetEntity.isRemoved()) {
            targetEntity = null;
            navigating = false;
            findAnimalTarget();
            if (targetEntity == null) {
                if (ticksRunning % 60 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' searching for animal ({})", steve.getSteveName(), resourceName);
                }
                return;
            }
        }

        double distToAnimal = steve.distanceTo(targetEntity);

        if (distToAnimal <= 3.5) {
            // Within attack range — strike
            if (ticksSinceLastAction >= 10) {
                steve.doHurtTarget(targetEntity);
                steve.swing(InteractionHand.MAIN_HAND, true);
                ticksSinceLastAction = 0;

                if (!targetEntity.isAlive() || targetEntity.isRemoved()) {
                    gathered++;
                    SteveMod.LOGGER.info("Steve '{}' killed animal for {} ({}/{})",
                        steve.getSteveName(), resourceName, gathered, quantity);
                    targetEntity = null;
                    navigating = false;

                    if (gathered >= quantity) {
                        steve.getNavigation().stop();
                        result = ActionResult.success("Gathered " + gathered + " " + resourceName);
                    }
                }
            }
            return;
        }

        // Walk toward animal
        if (!navigating) {
            steve.getNavigation().moveTo(targetEntity, 1.0);
            navigating = true;
            ticksSinceNavStart = 0;
        } else {
            ticksSinceNavStart++;
            if (steve.getNavigation().isDone() && distToAnimal > 3.5) {
                if (ticksSinceNavStart > NAVIGATION_TIMEOUT) {
                    SteveMod.LOGGER.info("Steve '{}' stuck navigating to animal, retargeting", steve.getSteveName());
                    targetEntity = null;
                    navigating = false;
                } else {
                    steve.getNavigation().moveTo(targetEntity, 1.0);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Search helpers
    // -----------------------------------------------------------------------

    /**
     * Scan the world surface for the nearest matching block within SEARCH_RADIUS.
     * Searches from sky down to ground level (Y:256 → -64) to stay near-surface.
     */
    private void findBlockTarget() {
        Block target = resolveBlock(resourceName);
        if (target == null || target == Blocks.AIR) {
            SteveMod.LOGGER.warn("Steve '{}' cannot resolve block for resource '{}'",
                steve.getSteveName(), resourceName);
            return;
        }

        BlockPos stevePos = steve.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                // Search full column — surface blocks may be at many Y-levels
                for (int y = 256; y >= -64; y--) {
                    BlockPos checkPos = new BlockPos(stevePos.getX() + x, y, stevePos.getZ() + z);
                    if (steve.level().getBlockState(checkPos).getBlock() == target) {
                        double dist = stevePos.distSqr(checkPos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = checkPos;
                        }
                        break; // Found the topmost instance in this column — move on
                    }
                }
            }
        }

        if (nearest != null) {
            targetBlock = nearest;
            SteveMod.LOGGER.info("Steve '{}' found {} at {} ({}m away)",
                steve.getSteveName(), resourceName, nearest, (int) Math.sqrt(nearestDist));
        }
    }

    /**
     * Find the nearest living animal whose drops match the requested resource.
     */
    private void findAnimalTarget() {
        String animalType = DROP_TO_ANIMAL.getOrDefault(resourceName, "");
        AABB searchBox = steve.getBoundingBox().inflate(SEARCH_RADIUS);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        LivingEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive() || living.isRemoved()) continue;
            if (entity instanceof SteveEntity) continue;
            if (entity instanceof net.minecraft.world.entity.player.Player) continue;

            String entityTypeName = entity.getType().toString().toLowerCase();
            if (!animalType.isEmpty() && !entityTypeName.contains(animalType)) continue;
            // If animalType is empty (fallback), match any passive animal
            if (animalType.isEmpty() && !(entity instanceof Animal)) continue;

            double dist = steve.distanceTo(living);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = living;
            }
        }

        if (nearest != null) {
            targetEntity = nearest;
            SteveMod.LOGGER.info("Steve '{}' found animal target: {} at {}m",
                steve.getSteveName(), nearest.getType(), (int) nearestDist);
        }
    }

    // -----------------------------------------------------------------------
    // Tree felling helpers
    // -----------------------------------------------------------------------

    /**
     * After breaking the base log, scan upward and queue all contiguous log blocks
     * in the trunk so they get broken too (fells the whole tree).
     */
    private void scanTrunkAbove(BlockPos basePos, Block logBlock) {
        trunkQueue.clear();
        BlockPos check = basePos.above();
        int maxHeight = 30; // Tallest possible tree in vanilla
        for (int i = 0; i < maxHeight; i++) {
            if (steve.level().getBlockState(check).getBlock() == logBlock) {
                trunkQueue.add(check);
                check = check.above();
            } else {
                break;
            }
        }
        if (!trunkQueue.isEmpty()) {
            SteveMod.LOGGER.info("Steve '{}' queued {} trunk logs to fell",
                steve.getSteveName(), trunkQueue.size());
        }
    }

    // -----------------------------------------------------------------------
    // Block breaking
    // -----------------------------------------------------------------------

    private void breakBlock(BlockPos pos) {
        // Swing animation then destroy
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().destroyBlock(pos, true);
    }

    // -----------------------------------------------------------------------
    // Name resolution
    // -----------------------------------------------------------------------

    /**
     * Normalise the raw resource string from the LLM into a canonical Minecraft block name.
     */
    private static String resolveResourceName(String raw) {
        String normalized = raw.toLowerCase().replace(" ", "_").replace("-", "_");
        return RESOURCE_ALIASES.getOrDefault(normalized, normalized);
    }

    /**
     * Look up a Block object by its Minecraft block name (without "minecraft:" prefix).
     * Returns null if the block is not found.
     */
    private static Block resolveBlock(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.contains(":") ? name : "minecraft:" + name;
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(key));
        return (block == Blocks.AIR && !name.equals("air")) ? null : block;
    }

    /** Returns true if the resource name represents a log/wood type. */
    private static boolean isLogBlock(String name) {
        return name != null && name.endsWith("_log");
    }
}
