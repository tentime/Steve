package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

/**
 * Sets the current position as Steve's home base.
 * Completes instantly (single-tick action).
 */
public class SetHomeAction extends BaseAction {

    public SetHomeAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        BlockPos pos = steve.blockPosition();
        steve.getMemory().setHomePosition(pos);

        SteveMod.LOGGER.info("Steve '{}' set home to [{}, {}, {}]",
            steve.getSteveName(), pos.getX(), pos.getY(), pos.getZ());

        result = ActionResult.success(
            "Home set to [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]");
    }

    @Override
    protected void onTick() {
        // Instant action — no tick needed
    }

    @Override
    protected void onCancel() {
        // Nothing to clean up
    }

    @Override
    public String getDescription() {
        return "Set home position";
    }
}
