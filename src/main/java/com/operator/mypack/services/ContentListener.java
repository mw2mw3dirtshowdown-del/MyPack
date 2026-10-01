package com.operator.mypack.services;

import com.operator.mypack.pack.ContentRegistry;

/**
 * Notified after the content registry was replaced. Called on the global region thread; implementations that touch
 * entities must hand the work to the entity's own scheduler.
 */
@FunctionalInterface
public interface ContentListener {
    void onContentReplaced(ContentRegistry.Snapshot snapshot);
}
