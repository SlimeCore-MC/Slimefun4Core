package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

/**
 * Thrown when a checkpoint cannot be created, read or restored.
 *
 * <p>The message names the checkpoint and the component involved so that an operator can act on
 * it without reading a stack trace.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public class CheckpointException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message What went wrong, safe to show to an operator
     */
    public CheckpointException(@Nonnull String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and an underlying cause.
     *
     * @param message What went wrong, safe to show to an operator
     * @param cause   The underlying failure
     */
    public CheckpointException(@Nonnull String message, @Nonnull Throwable cause) {
        super(message, cause);
    }
}
