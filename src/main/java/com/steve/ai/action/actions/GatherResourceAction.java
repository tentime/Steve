package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

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
    private static final int SEARCH_RADIUS = 24;
    private static final int SEARCH_HEIGHT_UP = 30;
    private static final int SEARCH_HEIGHT_DOWN = 10;
    private static final int MAX_TICKS = 6000;           // 5-minute timeout
    private static final int REACH_DISTANCE = 5;
    private static final int NAVIGATION_TIMEOUT = 100;
    private static final int CHEST_SEARCH_RADIUS = 8;

    // Mining animation: ticks of swinging before the block actually breaks
    // This gives visual feedback — arm swings + cracking overlay
    private static final int BREAK_TICKS = 10;           // ~0.5 seconds to break a block

    // Special marker: "find any log type, whichever is closest"
    private static final String ANY_LOG = "any_log";

    // All vanilla log blocks to check when resource is ANY_LOG
    private static final List<Block> ALL_LOG_BLOCKS = new ArrayList<>();
    static {
        String[] logNames = {
            "oak_log", "birch_log", "spruce_log", "jungle_log",
            "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log"
        };
        for (String name : logNames) {
            Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation("minecraft:" + name));
            if (b != null && b != Blocks.AIR) {
                ALL_LOG_BLOCKS.add(b);
            }
        }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private String resourceName;
    private int quantity;
    private int gathered;
    private int ticksRunning;
    private int ticksSinceNavStart;

    private BlockPos targetBlock;
    private Block targetBlockType;       // The actual Block we matched (matters for any_log)
    private LivingEntity targetEntity;
    private boolean isAnimalTarget;
    private boolean navigating;

    // Mining animation state
    private int breakProgress;           // Ticks spent mining current block (0 to BREAK_TICKS)
    private boolean isMining;            // True while doing the break animation
    private int breakerId;               // Unique ID for destroyBlockProgress packets

    // For tree felling: stack of log positions above the first one
    private final List<BlockPos> trunkQueue = new ArrayList<>();

    // Resource name aliases → canonical Minecraft block name
    private static final Map<String, String> RESOURCE_ALIASES = new HashMap<>() {{
        // Generic wood → find ANY nearby tree
        put("wood",        ANY_LOG);
        put("log",         ANY_LOG);
        put("logs",        ANY_LOG);
        put("tree",        ANY_LOG);
        put("trees",       ANY_LOG);
        // Specific wood types
        put("oak",         "oak_log");
        put("oak_log",     "oak_log");
        put("birch",       "birch_log");
        put("birch_log",   "birch_log");
        put("spruce",      "spruce_log");
        put("spruce_log",  "spruce_log");
        put("jungle",      "jungle_log");
        put("jungle_log",  "jungle_log");
        put("acacia",      "acacia_log");
        put("acacia_log",  "acacia_log");
        put("dark_oak",    "dark_oak_log");
        put("dark_oak_log","dark_oak_log");
        put("cherry",      "cherry_log");
        put("cherry_log",  "cherry_log");
        put("mangrove",    "mangrove_log");
        put("mangrove_log","mangrove_log");
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

    private static final Set<String> ANIMAL_DROPS = DROP_TO_ANIMAL.keySet();

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
        ticksSinceNavStart = 0;
        targetBlock = null;
        targetBlockType = null;
        targetEntity = null;
        navigating = false;
        breakProgress = 0;
        isMining = false;
        breakerId = steve.getId();  // Use entity ID for block break progress packets
        trunkQueue.clear();

        if (rawResource == null || rawResource.isBlank()) {
            result = ActionResult.failure("No resource specified for gather action");
            return;
        }

        resourceName = resolveResourceName(rawResource);
        isAnimalTarget = ANIMAL_DROPS.contains(resourceName);

        SteveMod.LOGGER.info("Steve '{}' starting gather: {} x{} (resolved from '{}')",
            steve.getSteveName(), resourceName, quantity, rawResource);

        steve.setFlying(false);
        steve.setSprinting(false);

        if (isAnimalTarget) {
            findAnimalTarget();
        } else {
            findBlockTarget();
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.getNavigation().stop();
            cancelBreakProgress();
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
        cancelBreakProgress();
        trunkQueue.clear();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceName + " (" + gathered + " collected)";
    }

    // -----------------------------------------------------------------------
    // Block breaking with visible animation
    // -----------------------------------------------------------------------

    /**
     * Start or continue the mining animation on a block.
     * Swings Steve's arm each tick, sends cracking overlay progress to clients,
     * and breaks the block after BREAK_TICKS ticks.
     *
     * @return true if the block was broken this tick, false if still mining
     */
    private boolean tickMineBlock(BlockPos pos) {
        // Look at the block we're mining
        steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        // Swing arm every other tick for a natural look
        if (breakProgress % 2 == 0) {
            steve.swing(InteractionHand.MAIN_HAND, true);
        }

        // Send block cracking progress to all nearby players
        // Progress is 0-9 where 9 is nearly broken, -1 clears it
        if (steve.level() instanceof ServerLevel serverLevel) {
            int stage = (int) ((float) breakProgress / BREAK_TICKS * 9.0F);
            stage = Math.min(stage, 9);
            serverLevel.destroyBlockProgress(breakerId, pos, stage);
        }

        breakProgress++;

        if (breakProgress >= BREAK_TICKS) {
            // Done mining — actually break the block
            if (steve.level() instanceof ServerLevel serverLevel) {
                // Clear the cracking overlay
                serverLevel.destroyBlockProgress(breakerId, pos, -1);
            }
            steve.swing(InteractionHand.MAIN_HAND, true);
            steve.level().destroyBlock(pos, true);  // true = drop items
            breakProgress = 0;
            isMining = false;
            return true;
        }

        isMining = true;
        return false;
    }

    /**
     * Clear any active block break progress overlay.
     */
    private void cancelBreakProgress() {
        if (isMining && targetBlock != null && steve.level() instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(breakerId, targetBlock, -1);
        }
        isMining = false;
        breakProgress = 0;
    }

    // -----------------------------------------------------------------------
    // Block gathering logic
    // -----------------------------------------------------------------------

    private void tickBlockGather() {
        // Drain trunk queue first (fell the whole tree)
        if (!trunkQueue.isEmpty()) {
            BlockPos nextLog = trunkQueue.get(0);
            if (targetBlockType != null && steve.level().getBlockState(nextLog).getBlock() == targetBlockType) {
                steve.getLookControl().setLookAt(nextLog.getX() + 0.5, nextLog.getY() + 0.5, nextLog.getZ() + 0.5);
                if (tickMineBlock(nextLog)) {
                    trunkQueue.remove(0);
                    gathered++;
                    SteveMod.LOGGER.info("Steve '{}' felled trunk log at {} ({}/{})",
                        steve.getSteveName(), nextLog, gathered, quantity);
                    if (gathered >= quantity) {
                        trunkQueue.clear();
                        finishGathering();
                    }
                }
            } else {
                // Log is gone, skip it
                trunkQueue.remove(0);
                cancelBreakProgress();
            }
            return;
        }

        // Find a target if we don't have one
        if (targetBlock == null) {
            findBlockTarget();
            if (targetBlock == null) {
                if (ticksRunning % 60 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' searching for {} ...", steve.getSteveName(), resourceName);
                }
                return;
            }
        }

        // Verify target block still exists
        if (targetBlockType == null || steve.level().getBlockState(targetBlock).getBlock() != targetBlockType) {
            targetBlock = null;
            targetBlockType = null;
            navigating = false;
            cancelBreakProgress();
            return;
        }

        double distToTarget = steve.blockPosition().distSqr(targetBlock);

        // Close enough to mine
        if (distToTarget <= REACH_DISTANCE * REACH_DISTANCE) {
            if (tickMineBlock(targetBlock)) {
                gathered++;
                SteveMod.LOGGER.info("Steve '{}' gathered {} at {} ({}/{})",
                    steve.getSteveName(), resourceName, targetBlock, gathered, quantity);

                // For logs: fell the whole trunk
                if (isLogBlock(targetBlockType)) {
                    scanTrunkAbove(targetBlock, targetBlockType);
                }

                targetBlock = null;
                navigating = false;

                if (gathered >= quantity) {
                    finishGathering();
                }
            }
            return;
        }

        // If we were mining but moved away, cancel the progress
        if (isMining) {
            cancelBreakProgress();
        }

        // Navigate to target
        if (!navigating) {
            steve.getNavigation().moveTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, 1.0);
            navigating = true;
            ticksSinceNavStart = 0;
            SteveMod.LOGGER.info("Steve '{}' walking to {} at {}", steve.getSteveName(), resourceName, targetBlock);
        } else {
            ticksSinceNavStart++;
            if (steve.getNavigation().isDone() && distToTarget > REACH_DISTANCE * REACH_DISTANCE) {
                if (ticksSinceNavStart > NAVIGATION_TIMEOUT) {
                    SteveMod.LOGGER.info("Steve '{}' stuck navigating to {}, searching for another",
                        steve.getSteveName(), targetBlock);
                    targetBlock = null;
                    targetBlockType = null;
                    navigating = false;
                } else {
                    steve.getNavigation().moveTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, 1.0);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Animal gathering logic
    // -----------------------------------------------------------------------

    private void tickAnimalGather() {
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
            if (ticksRunning % 10 == 0) {  // Attack every 10 ticks (0.5s)
                steve.doHurtTarget(targetEntity);
                steve.swing(InteractionHand.MAIN_HAND, true);

                if (!targetEntity.isAlive() || targetEntity.isRemoved()) {
                    gathered++;
                    SteveMod.LOGGER.info("Steve '{}' killed animal for {} ({}/{})",
                        steve.getSteveName(), resourceName, gathered, quantity);
                    targetEntity = null;
                    navigating = false;

                    if (gathered >= quantity) {
                        finishGathering();
                    }
                }
            }
            return;
        }

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
    // Completion — deposit into nearby chest if possible
    // -----------------------------------------------------------------------

    /**
     * Called when gathering is done. Tries to deposit items into a nearby chest,
     * then reports success.
     */
    private void finishGathering() {
        steve.getNavigation().stop();
        cancelBreakProgress();
        int deposited = tryDepositIntoChest();
        if (deposited > 0) {
            result = ActionResult.success(
                "Gathered " + gathered + " " + resourceName + " and deposited " + deposited + " items into nearby chest");
        } else {
            result = ActionResult.success("Gathered " + gathered + " " + resourceName);
        }
    }

    /**
     * Look for a chest within CHEST_SEARCH_RADIUS and dump Steve's inventory into it.
     * Returns the number of item stacks deposited.
     */
    private int tryDepositIntoChest() {
        BlockPos stevePos = steve.blockPosition();
        BlockPos nearestChest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -CHEST_SEARCH_RADIUS; x <= CHEST_SEARCH_RADIUS; x++) {
            for (int z = -CHEST_SEARCH_RADIUS; z <= CHEST_SEARCH_RADIUS; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos checkPos = new BlockPos(stevePos.getX() + x, stevePos.getY() + y, stevePos.getZ() + z);
                    if (steve.level().getBlockState(checkPos).getBlock() instanceof ChestBlock) {
                        double dist = stevePos.distSqr(checkPos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearestChest = checkPos;
                        }
                    }
                }
            }
        }

        if (nearestChest == null) return 0;

        BlockEntity be = steve.level().getBlockEntity(nearestChest);
        if (!(be instanceof ChestBlockEntity chest)) return 0;

        int deposited = 0;
        var inventory = steve.getInventoryContainer();
        if (inventory == null) return 0;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                ItemStack chestStack = chest.getItem(slot);
                if (chestStack.isEmpty()) {
                    chest.setItem(slot, stack.copy());
                    inventory.setItem(i, ItemStack.EMPTY);
                    deposited++;
                    break;
                } else if (ItemStack.isSameItemSameTags(chestStack, stack)
                        && chestStack.getCount() < chestStack.getMaxStackSize()) {
                    int space = chestStack.getMaxStackSize() - chestStack.getCount();
                    int transfer = Math.min(space, stack.getCount());
                    chestStack.grow(transfer);
                    stack.shrink(transfer);
                    if (stack.isEmpty()) {
                        inventory.setItem(i, ItemStack.EMPTY);
                        deposited++;
                        break;
                    }
                }
            }
        }

        if (deposited > 0) {
            chest.setChanged();
            SteveMod.LOGGER.info("Steve '{}' deposited {} item stacks into chest at {}",
                steve.getSteveName(), deposited, nearestChest);
        }

        return deposited;
    }

    // -----------------------------------------------------------------------
    // Search helpers
    // -----------------------------------------------------------------------

    /**
     * Scan nearby blocks for the nearest matching block.
     * If resourceName is ANY_LOG, checks all log types and picks the closest.
     */
    private void findBlockTarget() {
        boolean anyLog = ANY_LOG.equals(resourceName);

        List<Block> targets;
        if (anyLog) {
            targets = ALL_LOG_BLOCKS;
        } else {
            Block single = resolveBlock(resourceName);
            if (single == null || single == Blocks.AIR) {
                SteveMod.LOGGER.warn("Steve '{}' cannot resolve block for resource '{}' (got null or AIR)",
                    steve.getSteveName(), resourceName);
                return;
            }
            targets = List.of(single);
        }

        if (ticksRunning <= 1) {
            SteveMod.LOGGER.info("Steve '{}' searching for {} (candidates: {})",
                steve.getSteveName(), resourceName, anyLog ? "all log types" : targets.get(0));
        }

        BlockPos stevePos = steve.blockPosition();
        BlockPos nearest = null;
        Block nearestType = null;
        double nearestDist = Double.MAX_VALUE;

        int minY = stevePos.getY() - SEARCH_HEIGHT_DOWN;
        int maxY = stevePos.getY() + SEARCH_HEIGHT_UP;

        Set<Block> targetSet = new HashSet<>(targets);

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                for (int y = maxY; y >= minY; y--) {
                    BlockPos checkPos = new BlockPos(stevePos.getX() + x, y, stevePos.getZ() + z);
                    Block found = steve.level().getBlockState(checkPos).getBlock();
                    if (targetSet.contains(found)) {
                        double dist = stevePos.distSqr(checkPos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = checkPos;
                            nearestType = found;
                        }
                        break; // Found in this column, move on
                    }
                }
            }
        }

        if (nearest != null) {
            targetBlock = nearest;
            targetBlockType = nearestType;
            ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(nearestType);
            SteveMod.LOGGER.info("Steve '{}' found {} at {} ({}m away, type: {})",
                steve.getSteveName(), resourceName, nearest, (int) Math.sqrt(nearestDist), key);
        } else if (ticksRunning <= 1) {
            SteveMod.LOGGER.warn("Steve '{}' found NO '{}' blocks in {}x{}x{} area around {}",
                steve.getSteveName(), resourceName,
                SEARCH_RADIUS * 2, SEARCH_HEIGHT_UP + SEARCH_HEIGHT_DOWN, SEARCH_RADIUS * 2,
                stevePos);
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

    private void scanTrunkAbove(BlockPos basePos, Block logBlock) {
        trunkQueue.clear();
        BlockPos check = basePos.above();
        int maxHeight = 30;
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
    // Name resolution
    // -----------------------------------------------------------------------

    private static String resolveResourceName(String raw) {
        String normalized = raw.toLowerCase().replace(" ", "_").replace("-", "_");
        return RESOURCE_ALIASES.getOrDefault(normalized, normalized);
    }

    private static Block resolveBlock(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.contains(":") ? name : "minecraft:" + name;
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(key));
        return (block == Blocks.AIR && !name.equals("air")) ? null : block;
    }

    /** Returns true if the block is any type of log. */
    private static boolean isLogBlock(Block block) {
        if (block == null) return false;
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
        return key != null && key.getPath().endsWith("_log");
    }
}
