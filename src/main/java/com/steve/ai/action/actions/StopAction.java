package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

/**
 * Stop action — cancels all movement, stops navigation, and stands still.
 * Completes instantly.
 */
public class StopAction extends BaseAction {

    public StopAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        steve.getNavigation().stop();
        steve.setSprinting(false);
        steve.setFlying(false);

        SteveMod.LOGGER.info("Steve '{}' stopped all actions", steve.getSteveName());

        result = ActionResult.success("Stopped — standing still");
    }

    @Override
    protected void onTick() {
        // Instant action
    }

    @Override
    protected void onCancel() {
        // Nothing to clean up
    }

    @Override
    public String getDescription() {
        return "Stop all actions";
    }
}
