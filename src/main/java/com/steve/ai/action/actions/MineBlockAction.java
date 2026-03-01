package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private int searchRadius = 8;
    private int ticksRunning;
    private int ticksSinceLastTorch = 0;
    private BlockPos miningStartPos;
    private BlockPos currentTunnelPos;
    private int miningDirectionX = 0;
    private int miningDirectionZ = 0;
    private static final int MAX_TICKS = 24000;
    private static final int TORCH_INTERVAL = 100;
    private static final int MIN_LIGHT_LEVEL = 8;
    private static final int MAX_MINING_RADIUS = 5;

    // Mining animation constants
    private static final int BREAK_TICKS = 12;          // Ticks to break a block (~0.6s, slightly slower for stone/ore)
    private static final int TUNNEL_BREAK_TICKS = 8;    // Faster for tunnel clearing

    // Mining animation state
    private int breakProgress = 0;
    private BlockPos breakingPos = null;     // The block currently being animated
    private boolean isMining = false;
    private int breakerId;

    // Tunnel mining state — we break 3 blocks per tunnel step (center, above, below)
    private enum TunnelPhase { CENTER, ABOVE, BELOW, DONE }
    private TunnelPhase tunnelPhase = TunnelPhase.DONE;
    private BlockPos tunnelCenter;

    // Ore depth mappings for intelligent mining
    private static final Map<String, Integer> ORE_DEPTHS = new HashMap<>() {{
        put("iron_ore", 64);
        put("deepslate_iron_ore", -16);
        put("coal_ore", 96);
        put("copper_ore", 48);
        put("gold_ore", 32);
        put("deepslate_gold_ore", -16);
        put("diamond_ore", -59);
        put("deepslate_diamond_ore", -59);
        put("redstone_ore", 16);
        put("deepslate_redstone_ore", -32);
        put("lapis_ore", 0);
        put("deepslate_lapis_ore", -16);
        put("emerald_ore", 256);
    }};

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 8);
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastTorch = 0;
        breakProgress = 0;
        breakingPos = null;
        isMining = false;
        breakerId = steve.getId();
        tunnelPhase = TunnelPhase.DONE;

        targetBlock = parseBlock(blockName);

        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }

        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 eyePos = nearestPlayer.getEyePosition(1.0F);
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();

            double angle = Math.atan2(lookVec.z, lookVec.x) * 180.0 / Math.PI;
            angle = (angle + 360) % 360;

            if (angle >= 315 || angle < 45) {
                miningDirectionX = 1; miningDirectionZ = 0;
            } else if (angle >= 45 && angle < 135) {
                miningDirectionX = 0; miningDirectionZ = 1;
            } else if (angle >= 135 && angle < 225) {
                miningDirectionX = -1; miningDirectionZ = 0;
            } else {
                miningDirectionX = 0; miningDirectionZ = -1;
            }

            net.minecraft.world.phys.Vec3 targetPos = eyePos.add(lookVec.scale(3));

            BlockPos lookTarget = new BlockPos(
                (int)Math.floor(targetPos.x),
                (int)Math.floor(targetPos.y),
                (int)Math.floor(targetPos.z)
            );

            miningStartPos = lookTarget;
            for (int y = lookTarget.getY(); y > lookTarget.getY() - 20 && y > -64; y--) {
                BlockPos groundCheck = new BlockPos(lookTarget.getX(), y, lookTarget.getZ());
                if (steve.level().getBlockState(groundCheck).isSolid()) {
                    miningStartPos = groundCheck.above();
                    break;
                }
            }

            currentTunnelPos = miningStartPos;
            steve.teleportTo(miningStartPos.getX() + 0.5, miningStartPos.getY(), miningStartPos.getZ() + 0.5);

            String[] dirNames = {"North", "East", "South", "West"};
            int dirIndex = miningDirectionZ == -1 ? 0 : (miningDirectionX == 1 ? 1 : (miningDirectionZ == 1 ? 2 : 3));
            SteveMod.LOGGER.info("Steve '{}' mining {} in direction: {}",
                steve.getSteveName(), targetBlock.getName().getString(), dirNames[dirIndex]);
        } else {
            miningStartPos = steve.blockPosition();
            currentTunnelPos = miningStartPos;
            miningDirectionX = 1;
            miningDirectionZ = 0;
        }

        steve.setFlying(true);
        equipIronPickaxe();

        SteveMod.LOGGER.info("Steve '{}' mining {} - staying at {}",
            steve.getSteveName(), targetBlock.getName().getString(), miningStartPos);

        findNextBlock();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastTorch++;

        if (ticksRunning > MAX_TICKS) {
            cleanupMining();
            result = ActionResult.failure("Mining timeout - only found " + minedCount + " blocks");
            return;
        }

        if (ticksSinceLastTorch >= TORCH_INTERVAL) {
            placeTorchIfDark();
            ticksSinceLastTorch = 0;
        }

        // If we're in the middle of a tunnel mining sequence, continue it
        if (tunnelPhase != TunnelPhase.DONE) {
            tickTunnelMining();
            return;
        }

        // If we're in the middle of breaking an ore block, continue
        if (isMining && breakingPos != null) {
            tickOreBreaking();
            return;
        }

        // Find next ore target
        if (currentTarget == null) {
            findNextBlock();

            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    cleanupMining();
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                } else {
                    // No ore found — start tunnel mining sequence
                    startTunnelMining();
                    return;
                }
            }
        }

        // We have an ore target — move to it and start breaking
        if (steve.level().getBlockState(currentTarget).getBlock() == targetBlock) {
            steve.teleportTo(currentTarget.getX() + 0.5, currentTarget.getY(), currentTarget.getZ() + 0.5);
            breakingPos = currentTarget;
            breakProgress = 0;
            isMining = true;
            // The actual breaking happens in tickOreBreaking() next tick
        } else {
            currentTarget = null;
        }
    }

    /**
     * Animate breaking an ore block over multiple ticks.
     */
    private void tickOreBreaking() {
        if (breakingPos == null || steve.level().getBlockState(breakingPos).getBlock() != targetBlock) {
            cancelBreakProgress();
            currentTarget = null;
            return;
        }

        // Look at the block
        steve.getLookControl().setLookAt(
            breakingPos.getX() + 0.5, breakingPos.getY() + 0.5, breakingPos.getZ() + 0.5);

        // Swing arm
        if (breakProgress % 2 == 0) {
            steve.swing(InteractionHand.MAIN_HAND, true);
        }

        // Send cracking progress
        if (steve.level() instanceof ServerLevel serverLevel) {
            int stage = (int) ((float) breakProgress / BREAK_TICKS * 9.0F);
            stage = Math.min(stage, 9);
            serverLevel.destroyBlockProgress(breakerId, breakingPos, stage);
        }

        breakProgress++;

        if (breakProgress >= BREAK_TICKS) {
            // Block breaks
            if (steve.level() instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlockProgress(breakerId, breakingPos, -1);
            }
            steve.swing(InteractionHand.MAIN_HAND, true);
            steve.level().destroyBlock(breakingPos, true);
            minedCount++;

            SteveMod.LOGGER.info("Steve '{}' mined {} at {} - Total: {}/{}",
                steve.getSteveName(), targetBlock.getName().getString(), breakingPos,
                minedCount, targetQuantity);

            breakProgress = 0;
            breakingPos = null;
            isMining = false;
            currentTarget = null;

            if (minedCount >= targetQuantity) {
                cleanupMining();
                result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
            }
        }
    }

    /**
     * Start a tunnel mining sequence: breaks center, above, below blocks with animation.
     */
    private void startTunnelMining() {
        tunnelCenter = currentTunnelPos;
        tunnelPhase = TunnelPhase.CENTER;
        breakProgress = 0;
        breakingPos = tunnelCenter;

        // Move Steve to the tunnel position
        steve.teleportTo(tunnelCenter.getX() + 0.5, tunnelCenter.getY(), tunnelCenter.getZ() + 0.5);
    }

    /**
     * Continue the tunnel mining sequence — animate each of the 3 blocks.
     */
    private void tickTunnelMining() {
        BlockPos posToBreak;
        switch (tunnelPhase) {
            case CENTER: posToBreak = tunnelCenter; break;
            case ABOVE:  posToBreak = tunnelCenter.above(); break;
            case BELOW:  posToBreak = tunnelCenter.below(); break;
            default: return;
        }

        BlockState state = steve.level().getBlockState(posToBreak);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
            // Skip this phase — block is air or bedrock
            advanceTunnelPhase();
            return;
        }

        // Look at block
        steve.getLookControl().setLookAt(
            posToBreak.getX() + 0.5, posToBreak.getY() + 0.5, posToBreak.getZ() + 0.5);

        if (breakProgress % 2 == 0) {
            steve.swing(InteractionHand.MAIN_HAND, true);
        }

        if (steve.level() instanceof ServerLevel serverLevel) {
            int stage = (int) ((float) breakProgress / TUNNEL_BREAK_TICKS * 9.0F);
            stage = Math.min(stage, 9);
            serverLevel.destroyBlockProgress(breakerId, posToBreak, stage);
        }

        breakProgress++;

        if (breakProgress >= TUNNEL_BREAK_TICKS) {
            if (steve.level() instanceof ServerLevel serverLevel) {
                serverLevel.destroyBlockProgress(breakerId, posToBreak, -1);
            }
            steve.swing(InteractionHand.MAIN_HAND, true);
            steve.level().destroyBlock(posToBreak, true);
            SteveMod.LOGGER.info("Steve '{}' mining tunnel at {}", steve.getSteveName(), posToBreak);

            breakProgress = 0;
            advanceTunnelPhase();
        }
    }

    private void advanceTunnelPhase() {
        switch (tunnelPhase) {
            case CENTER:
                tunnelPhase = TunnelPhase.ABOVE;
                breakProgress = 0;
                break;
            case ABOVE:
                tunnelPhase = TunnelPhase.BELOW;
                breakProgress = 0;
                break;
            case BELOW:
            default:
                // Done with this tunnel step — advance forward
                tunnelPhase = TunnelPhase.DONE;
                breakProgress = 0;
                breakingPos = null;
                currentTunnelPos = currentTunnelPos.offset(miningDirectionX, 0, miningDirectionZ);
                break;
        }
    }

    private void cancelBreakProgress() {
        if (breakingPos != null && steve.level() instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(breakerId, breakingPos, -1);
        }
        isMining = false;
        breakProgress = 0;
        breakingPos = null;
    }

    private void cleanupMining() {
        cancelBreakProgress();
        steve.setFlying(false);
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        tunnelPhase = TunnelPhase.DONE;
    }

    @Override
    protected void onCancel() {
        cleanupMining();
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Mine " + targetQuantity + " " + targetBlock.getName().getString() + " (" + minedCount + " found)";
    }

    private void placeTorchIfDark() {
        BlockPos stevePos = steve.blockPosition();
        int lightLevel = steve.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, stevePos);

        if (lightLevel < MIN_LIGHT_LEVEL) {
            BlockPos torchPos = findTorchPosition(stevePos);

            if (torchPos != null && steve.level().getBlockState(torchPos).isAir()) {
                steve.level().setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed torch at {} (light level was {})",
                    steve.getSteveName(), torchPos, lightLevel);
                steve.swing(InteractionHand.MAIN_HAND, true);
            }
        }
    }

    private BlockPos findTorchPosition(BlockPos center) {
        BlockPos floorPos = center.below();
        if (steve.level().getBlockState(floorPos).isSolid() &&
            steve.level().getBlockState(center).isAir()) {
            return center;
        }

        BlockPos[] wallPositions = {
            center.north(), center.south(), center.east(), center.west()
        };

        for (BlockPos wallPos : wallPositions) {
            if (steve.level().getBlockState(wallPos).isSolid() &&
                steve.level().getBlockState(center).isAir()) {
                return center;
            }
        }

        return null;
    }

    private void findNextBlock() {
        List<BlockPos> foundBlocks = new ArrayList<>();

        for (int distance = 0; distance < 20; distance++) {
            BlockPos checkPos = currentTunnelPos.offset(miningDirectionX * distance, 0, miningDirectionZ * distance);

            for (int y = -1; y <= 1; y++) {
                BlockPos orePos = checkPos.offset(0, y, 0);
                if (steve.level().getBlockState(orePos).getBlock() == targetBlock) {
                    foundBlocks.add(orePos);
                }
            }
        }

        if (!foundBlocks.isEmpty()) {
            currentTarget = foundBlocks.stream()
                .min((a, b) -> Double.compare(a.distSqr(currentTunnelPos), b.distSqr(currentTunnelPos)))
                .orElse(null);

            if (currentTarget != null) {
                SteveMod.LOGGER.info("Steve '{}' found {} ahead in tunnel at {}",
                    steve.getSteveName(), targetBlock.getName().getString(), currentTarget);
            }
        }
    }

    private void equipIronPickaxe() {
        net.minecraft.world.item.ItemStack pickaxe = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.IRON_PICKAXE
        );
        steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, pickaxe);
        SteveMod.LOGGER.info("Steve '{}' equipped iron pickaxe for mining", steve.getSteveName());
    }

    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = steve.level().players();

        if (players.isEmpty()) return null;

        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");

        Map<String, String> resourceToOre = new HashMap<>() {{
            put("iron", "iron_ore");
            put("diamond", "diamond_ore");
            put("coal", "coal_ore");
            put("gold", "gold_ore");
            put("copper", "copper_ore");
            put("redstone", "redstone_ore");
            put("lapis", "lapis_ore");
            put("emerald", "emerald_ore");
            put("stone", "stone");
            put("cobblestone", "cobblestone");
            put("obsidian", "obsidian");
            put("netherrack", "netherrack");
            put("deepslate", "deepslate");
            put("ancient_debris", "ancient_debris");
            put("glowstone", "glowstone");
            put("amethyst", "amethyst_block");
        }};

        if (resourceToOre.containsKey(blockName)) {
            blockName = resourceToOre.get(blockName);
        }

        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }

        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        return BuiltInRegistries.BLOCK.get(resourceLocation);
    }
}
