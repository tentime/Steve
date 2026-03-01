package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Return-home action — walks to the saved home position and deposits all
 * inventory items into the nearest chest(s).
 *
 * <p>Phases:</p>
 * <ol>
 *   <li>Navigate to home position</li>
 *   <li>Search for chests within 8 blocks of home</li>
 *   <li>Deposit all inventory items into found chests</li>
 * </ol>
 *
 * <p>If no home is set, the action fails immediately with an informative message.</p>
 */
public class ReturnHomeAction extends BaseAction {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------
    private static final int MAX_TICKS = 6000;          // 5-minute timeout
    private static final int CHEST_SEARCH_RADIUS = 8;   // Blocks around home to search for chests
    private static final int REACH_DISTANCE = 4;        // Must be within this to interact with chest
    private static final int NAVIGATION_TIMEOUT = 200;  // Ticks before retrying navigation

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private enum Phase { WALKING_HOME, FINDING_CHEST, DEPOSITING, DONE }

    private Phase phase;
    private BlockPos homePos;
    private BlockPos chestPos;
    private int ticksRunning;
    private int ticksSinceNavStart;
    private int itemsDeposited;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public ReturnHomeAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    // -----------------------------------------------------------------------
    // BaseAction implementation
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        ticksRunning = 0;
        ticksSinceNavStart = 0;
        itemsDeposited = 0;
        chestPos = null;

        // Check if home is set
        homePos = steve.getMemory().getHomePosition();
        if (homePos == null) {
            result = ActionResult.failure(
                "No home set! Tell me to 'set home' first at the location you want as home base.");
            return;
        }

        // Check if Steve even has items to deposit
        if (!steve.hasItemsInInventory()) {
            // Still walk home, just nothing to deposit
            SteveMod.LOGGER.info("Steve '{}' returning home with empty inventory", steve.getSteveName());
        }

        phase = Phase.WALKING_HOME;
        steve.setFlying(false);
        steve.setSprinting(false);

        // Start navigation to home
        steve.getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.0);
        SteveMod.LOGGER.info("Steve '{}' returning home to [{}, {}, {}]",
            steve.getSteveName(), homePos.getX(), homePos.getY(), homePos.getZ());
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.getNavigation().stop();
            result = ActionResult.failure("Return home timed out");
            return;
        }

        switch (phase) {
            case WALKING_HOME -> tickWalkHome();
            case FINDING_CHEST -> tickFindChest();
            case DEPOSITING -> tickDeposit();
            case DONE -> { /* should not happen */ }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setSprinting(false);
    }

    @Override
    public String getDescription() {
        return switch (phase) {
            case WALKING_HOME -> "Walking home to " + homePos;
            case FINDING_CHEST -> "Looking for chests near home";
            case DEPOSITING -> "Depositing items (" + itemsDeposited + " deposited)";
            case DONE -> "Home — deposited " + itemsDeposited + " items";
            default -> "Returning home";
        };
    }

    // -----------------------------------------------------------------------
    // Phase: Walk to home
    // -----------------------------------------------------------------------

    private void tickWalkHome() {
        double distToHome = steve.blockPosition().distSqr(homePos);

        // Close enough to home (within 4 blocks)
        if (distToHome <= REACH_DISTANCE * REACH_DISTANCE) {
            steve.getNavigation().stop();
            SteveMod.LOGGER.info("Steve '{}' arrived at home", steve.getSteveName());

            if (steve.hasItemsInInventory()) {
                phase = Phase.FINDING_CHEST;
            } else {
                result = ActionResult.success("Returned home (inventory was empty)");
            }
            return;
        }

        // Navigation management
        ticksSinceNavStart++;
        if (steve.getNavigation().isDone() && distToHome > REACH_DISTANCE * REACH_DISTANCE) {
            if (ticksSinceNavStart > NAVIGATION_TIMEOUT) {
                // Stuck — teleport closer (similar to IdleFollowAction logic)
                double dx = homePos.getX() - steve.getX();
                double dz = homePos.getZ() - steve.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 2) {
                    double moveAmount = Math.min(8.0, dist - 2);
                    steve.teleportTo(
                        steve.getX() + (dx / dist) * moveAmount,
                        steve.getY(),
                        steve.getZ() + (dz / dist) * moveAmount
                    );
                    SteveMod.LOGGER.info("Steve '{}' teleported closer to home (stuck)", steve.getSteveName());
                }
                ticksSinceNavStart = 0;
            } else {
                // Retry navigation
                steve.getNavigation().moveTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.0);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Phase: Find nearby chest
    // -----------------------------------------------------------------------

    private void tickFindChest() {
        chestPos = findNearestChest();

        if (chestPos != null) {
            SteveMod.LOGGER.info("Steve '{}' found chest at {} near home", steve.getSteveName(), chestPos);
            phase = Phase.DEPOSITING;
        } else {
            // No chest found — drop items on the ground at home instead
            SteveMod.LOGGER.info("Steve '{}' no chest near home — dropping items on ground", steve.getSteveName());
            dropAllItems();
            result = ActionResult.success("Returned home and dropped " + itemsDeposited + " item stacks (no chest found)");
        }
    }

    // -----------------------------------------------------------------------
    // Phase: Deposit items into chest
    // -----------------------------------------------------------------------

    private void tickDeposit() {
        BlockEntity be = steve.level().getBlockEntity(chestPos);
        if (!(be instanceof ChestBlockEntity chest)) {
            // Chest was broken or something — drop on ground instead
            SteveMod.LOGGER.warn("Steve '{}' chest at {} is gone, dropping items", steve.getSteveName(), chestPos);
            dropAllItems();
            result = ActionResult.success("Returned home and dropped " + itemsDeposited + " item stacks (chest destroyed)");
            return;
        }

        SimpleContainer steveInv = steve.getInventoryContainer();
        boolean depositedAnything = false;

        for (int i = 0; i < steveInv.getContainerSize(); i++) {
            ItemStack stack = steveInv.getItem(i);
            if (stack.isEmpty()) continue;

            // Try to insert into chest
            ItemStack remaining = insertIntoChest(chest, stack.copy());
            if (remaining.getCount() < stack.getCount()) {
                depositedAnything = true;
                itemsDeposited++;
            }

            if (remaining.isEmpty()) {
                steveInv.setItem(i, ItemStack.EMPTY);
            } else {
                steveInv.setItem(i, remaining);
            }
        }

        if (!steve.hasItemsInInventory()) {
            // All deposited
            result = ActionResult.success("Returned home and deposited " + itemsDeposited + " item stacks into chest");
            SteveMod.LOGGER.info("Steve '{}' deposited all items ({} stacks) into chest at {}",
                steve.getSteveName(), itemsDeposited, chestPos);
        } else if (!depositedAnything) {
            // Chest is full — try finding another chest or drop
            BlockPos altChest = findNearestChest(chestPos);
            if (altChest != null) {
                chestPos = altChest;
                SteveMod.LOGGER.info("Steve '{}' chest full, trying another at {}", steve.getSteveName(), altChest);
            } else {
                dropAllItems();
                result = ActionResult.success(
                    "Returned home, deposited " + itemsDeposited + " stacks — chest full, dropped remaining on ground");
            }
        }
        // else: partial deposit — will retry next tick (in case of lag)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Find the nearest chest block within CHEST_SEARCH_RADIUS of homePos.
     */
    private BlockPos findNearestChest() {
        return findNearestChest(null);
    }

    /**
     * Find the nearest chest near home, optionally excluding a given position.
     */
    private BlockPos findNearestChest(BlockPos exclude) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -CHEST_SEARCH_RADIUS; x <= CHEST_SEARCH_RADIUS; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -CHEST_SEARCH_RADIUS; z <= CHEST_SEARCH_RADIUS; z++) {
                    BlockPos checkPos = homePos.offset(x, y, z);
                    if (exclude != null && checkPos.equals(exclude)) continue;

                    BlockState state = steve.level().getBlockState(checkPos);
                    if (state.getBlock() instanceof ChestBlock) {
                        double dist = homePos.distSqr(checkPos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = checkPos;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Insert an item stack into a chest's inventory. Returns leftovers.
     */
    private ItemStack insertIntoChest(ChestBlockEntity chest, ItemStack stack) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack chestSlot = chest.getItem(slot);
            if (chestSlot.isEmpty()) {
                chest.setItem(slot, stack);
                chest.setChanged();
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameTags(chestSlot, stack)) {
                int canFit = chestSlot.getMaxStackSize() - chestSlot.getCount();
                if (canFit > 0) {
                    int transfer = Math.min(canFit, stack.getCount());
                    chestSlot.grow(transfer);
                    stack.shrink(transfer);
                    chest.setChanged();
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }
        return stack;
    }

    /**
     * Drop all remaining inventory items on the ground at Steve's feet.
     */
    private void dropAllItems() {
        SimpleContainer steveInv = steve.getInventoryContainer();
        for (int i = 0; i < steveInv.getContainerSize(); i++) {
            ItemStack stack = steveInv.getItem(i);
            if (!stack.isEmpty()) {
                steve.spawnAtLocation(stack.copy());
                steveInv.setItem(i, ItemStack.EMPTY);
                itemsDeposited++;
            }
        }
    }
}
