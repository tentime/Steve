package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Gather a surface resource (wood, sand, stone, plants, etc.).
 *
 * <p>Improvements in this revision:</p>
 * <ul>
 *   <li><b>Bounded exploration</b>: after 3 consecutive scan failures Steve walks
 *       a random direction; he won't wander more than 80 blocks from his start
 *       position. Maximum 5 exploration attempts before giving up.</li>
 *   <li><b>Block blacklist</b>: positions that the pathfinder fails to reach twice
 *       are added to a skip-set so we never waste time on them again.</li>
 *   <li><b>Bottom-up Y search</b>: when scanning for log blocks the Y range is
 *       iterated from low to high so we find the base of a tree first (easier to
 *       path to).</li>
 *   <li><b>findTreeBase()</b>: given any log block, scans downward to return the
 *       lowest connected log in that column.</li>
 * </ul>
 */
public class GatherResourceAction extends BaseAction {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int SCAN_RADIUS           = 24;   // XZ radius for block search
    private static final int SCAN_HEIGHT           = 16;   // Y half-range for block search
    private static final int REACH_DISTANCE_SQ     = 9;    // 3 blocks
    private static final int MAX_TICKS             = 6000; // 5-minute hard timeout
    private static final int NAV_TIMEOUT_TICKS     = 100;  // Ticks before pathfind retry
    private static final int STUCK_THRESHOLD       = 200;  // Ticks without progress = stuck
    private static final int EXPLORATION_THRESHOLD = 3;    // Consecutive scan fails before exploring
    private static final int MAX_EXPLORATION_ATTEMPTS = 5; // Give up after this many random walks
    private static final int EXPLORE_RADIUS        = 20;   // Random walk distance
    private static final int MAX_WANDER_DIST_SQ    = 80 * 80; // Max 80 blocks from start
    private static final int PATHFIND_FAIL_LIMIT   = 2;    // Blacklist after this many path failures

    // -----------------------------------------------------------------------
    // Log block type aliases
    // -----------------------------------------------------------------------
    private static final Map<String, String[]> RESOURCE_ALIASES = new HashMap<>();
    static {
        // any_log → try all vanilla log types
        RESOURCE_ALIASES.put("any_log", new String[]{
            "oak_log", "birch_log", "spruce_log", "jungle_log",
            "acacia_log", "dark_oak_log", "cherry_log", "mangrove_log"
        });
        // Generic names that should resolve to any_log
        RESOURCE_ALIASES.put("wood",   new String[]{"any_log"});
        RESOURCE_ALIASES.put("log",    new String[]{"any_log"});
        RESOURCE_ALIASES.put("logs",   new String[]{"any_log"});
        RESOURCE_ALIASES.put("tree",   new String[]{"any_log"});
        RESOURCE_ALIASES.put("trees",  new String[]{"any_log"});
        RESOURCE_ALIASES.put("timber", new String[]{"any_log"});
    }

    // -----------------------------------------------------------------------
    // State fields
    // -----------------------------------------------------------------------
    private String   resourceName;
    private int      quantityGoal;
    private int      quantityGathered;
    private BlockPos targetPos;
    private int      ticksRunning;
    private int      ticksAttemptingNav;
    private int      ticksWithoutProgress;
    private BlockPos lastPos;
    private int      consecutiveScanFailures;
    private int      explorationAttempts;
    private BlockPos startPos;                       // Where Steve was when the action started
    private final Set<BlockPos> blacklist = new HashSet<>();
    private final Map<BlockPos, Integer> pathFailCount = new HashMap<>();

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
        resourceName      = task.getStringParameter("resource");
        quantityGoal      = task.getIntParameter("quantity", 16);
        quantityGathered  = 0;
        targetPos         = null;
        ticksRunning      = 0;
        ticksAttemptingNav = 0;
        ticksWithoutProgress = 0;
        lastPos           = steve.blockPosition();
        consecutiveScanFailures = 0;
        explorationAttempts = 0;
        startPos          = steve.blockPosition();

        if (resourceName == null || resourceName.isBlank()) {
            result = ActionResult.failure("No resource specified");
            return;
        }

        resourceName = resourceName.toLowerCase().trim();
        SteveMod.LOGGER.info("Steve '{}' gathering {} x{}",
            steve.getSteveName(), resourceName, quantityGoal);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.getNavigation().stop();
            result = ActionResult.failure(
                "Timed out after " + quantityGathered + "/" + quantityGoal + " " + resourceName);
            return;
        }

        // Progress tracking
        BlockPos currentPos = steve.blockPosition();
        if (!currentPos.equals(lastPos)) {
            ticksWithoutProgress = 0;
            lastPos = currentPos;
        } else {
            ticksWithoutProgress++;
        }

        if (targetPos == null) {
            scanForTarget();
        } else {
            tickApproachAndBreak();
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Gather " + resourceName + " (" + quantityGathered + "/" + quantityGoal + ")";
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    private void scanForTarget() {
        BlockPos found = findNearestResource();

        if (found != null) {
            targetPos = found;
            consecutiveScanFailures = 0;
            ticksAttemptingNav = 0;
            steve.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0);
            SteveMod.LOGGER.info("Steve '{}' targeting {} at {}", steve.getSteveName(), resourceName, targetPos);
        } else {
            consecutiveScanFailures++;
            SteveMod.LOGGER.info("Steve '{}' scan #{} failed for {}",
                steve.getSteveName(), consecutiveScanFailures, resourceName);

            if (consecutiveScanFailures >= EXPLORATION_THRESHOLD) {
                if (explorationAttempts >= MAX_EXPLORATION_ATTEMPTS) {
                    result = ActionResult.failure(
                        "Could not find " + resourceName + " after " + explorationAttempts + " explorations. Gathered " + quantityGathered);
                } else {
                    exploreRandomly();
                }
            }
        }
    }

    /**
     * Walk in a random direction to search new terrain.
     * Respects the MAX_WANDER_DIST_SQ boundary around startPos.
     */
    private void exploreRandomly() {
        explorationAttempts++;
        consecutiveScanFailures = 0;

        Random rng = new Random();
        double angle = rng.nextDouble() * 2 * Math.PI;

        // Bias back toward start if we've wandered too far
        BlockPos cur = steve.blockPosition();
        double dxFromStart = cur.getX() - startPos.getX();
        double dzFromStart = cur.getZ() - startPos.getZ();
        double distSqFromStart = dxFromStart * dxFromStart + dzFromStart * dzFromStart;

        if (distSqFromStart > MAX_WANDER_DIST_SQ) {
            // Point back toward start + small random offset
            double backAngle = Math.atan2(-dzFromStart, -dxFromStart);
            angle = backAngle + (rng.nextDouble() - 0.5) * Math.PI * 0.5;
            SteveMod.LOGGER.info("Steve '{}' too far from start, biasing back", steve.getSteveName());
        }

        double dx = Math.cos(angle) * EXPLORE_RADIUS;
        double dz = Math.sin(angle) * EXPLORE_RADIUS;
        BlockPos exploreTarget = new BlockPos(
            (int)(cur.getX() + dx),
            cur.getY(),
            (int)(cur.getZ() + dz)
        );

        steve.getNavigation().moveTo(exploreTarget.getX() + 0.5, exploreTarget.getY(), exploreTarget.getZ() + 0.5, 1.0);
        SteveMod.LOGGER.info("Steve '{}' exploring toward {} (attempt {}/{})",
            steve.getSteveName(), exploreTarget, explorationAttempts, MAX_EXPLORATION_ATTEMPTS);
    }

    // -----------------------------------------------------------------------
    // Approach & break
    // -----------------------------------------------------------------------

    private void tickApproachAndBreak() {
        double distSq = steve.blockPosition().distSqr(targetPos);

        if (distSq <= REACH_DISTANCE_SQ) {
            // In range — break the block
            breakBlock(targetPos);
            quantityGathered++;
            targetPos = null;
            ticksAttemptingNav = 0;
            ticksWithoutProgress = 0;

            SteveMod.LOGGER.info("Steve '{}' gathered {} ({}/{})",
                steve.getSteveName(), resourceName, quantityGathered, quantityGoal);

            if (quantityGathered >= quantityGoal) {
                steve.getNavigation().stop();
                result = ActionResult.success(
                    "Gathered " + quantityGathered + " " + resourceName);
            }
            return;
        }

        // Navigation management
        ticksAttemptingNav++;
        boolean navDone = steve.getNavigation().isDone();

        if (ticksWithoutProgress > STUCK_THRESHOLD) {
            SteveMod.LOGGER.info("Steve '{}' stuck going to {}, blacklisting", steve.getSteveName(), targetPos);
            blacklist.add(targetPos);
            targetPos = null;
            ticksWithoutProgress = 0;
            ticksAttemptingNav = 0;
            steve.getNavigation().stop();
            return;
        }

        if (navDone && distSq > REACH_DISTANCE_SQ) {
            int fails = pathFailCount.merge(targetPos, 1, Integer::sum);
            if (fails >= PATHFIND_FAIL_LIMIT) {
                SteveMod.LOGGER.info("Steve '{}' blacklisting unreachable {}", steve.getSteveName(), targetPos);
                blacklist.add(targetPos);
                pathFailCount.remove(targetPos);
                targetPos = null;
                ticksAttemptingNav = 0;
            } else if (ticksAttemptingNav > NAV_TIMEOUT_TICKS) {
                // Retry navigation
                steve.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0);
                ticksAttemptingNav = 0;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Block-breaking
    // -----------------------------------------------------------------------

    private void breakBlock(BlockPos pos) {
        // Remove from world
        steve.level().destroyBlock(pos, true);
    }

    // -----------------------------------------------------------------------
    // Resource scanning
    // -----------------------------------------------------------------------

    private BlockPos findNearestResource() {
        BlockPos stevePos = steve.blockPosition();
        String[] blockNames = resolveResourceNames(resourceName);

        BlockPos nearest  = null;
        double   nearestD = Double.MAX_VALUE;

        // Iterate Y bottom-up so we find tree bases first
        for (int y = -SCAN_HEIGHT; y <= SCAN_HEIGHT; y++) {
            for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos checkPos = stevePos.offset(x, y, z);

                    if (blacklist.contains(checkPos)) continue;

                    BlockState state = steve.level().getBlockState(checkPos);
                    String blockId = getBlockId(state);

                    if (matchesAny(blockId, blockNames)) {
                        BlockPos usePos = isLogBlock(blockId)
                            ? findTreeBase(checkPos)
                            : checkPos;

                        if (blacklist.contains(usePos)) continue;

                        double dist = stevePos.distSqr(usePos);
                        if (dist < nearestD) {
                            nearestD = dist;
                            nearest  = usePos;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Given a log block position, scan downward to find the lowest
     * connected log in the same column (i.e., the tree base).
     */
    private BlockPos findTreeBase(BlockPos logPos) {
        BlockPos cur = logPos;
        while (true) {
            BlockPos below = cur.below();
            BlockState belowState = steve.level().getBlockState(below);
            String belowId = getBlockId(belowState);
            if (isLogBlock(belowId)) {
                cur = below;
            } else {
                break;
            }
        }
        return cur;
    }

    private boolean isLogBlock(String blockId) {
        return blockId.endsWith("_log") || blockId.endsWith("_wood");
    }

    // -----------------------------------------------------------------------
    // Name resolution helpers
    // -----------------------------------------------------------------------

    /**
     * Recursively resolve a resource name through the alias table.
     * Returns an array of concrete Minecraft block IDs to search for.
     */
    private String[] resolveResourceNames(String name) {
        if (RESOURCE_ALIASES.containsKey(name)) {
            String[] aliases = RESOURCE_ALIASES.get(name);
            // Check if the alias itself needs further resolution (e.g. "any_log")
            if (aliases.length == 1 && RESOURCE_ALIASES.containsKey(aliases[0])) {
                return RESOURCE_ALIASES.get(aliases[0]);
            }
            return aliases;
        }
        return new String[]{name};
    }

    private boolean matchesAny(String blockId, String[] names) {
        for (String name : names) {
            if (blockId.equals(name) || blockId.equals("minecraft:" + name)) return true;
        }
        return false;
    }

    private String getBlockId(BlockState state) {
        return state.getBlock().getDescriptionId()
            .replace("block.minecraft.", "")
            .replace("block.", "");
    }
}
