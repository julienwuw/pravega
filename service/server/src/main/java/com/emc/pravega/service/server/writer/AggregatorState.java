/**
 *
 *  Copyright (c) 2017 Dell Inc., or its subsidiaries.
 *
 */
package com.emc.pravega.service.server.writer;

/**
 * Defines various states that the SegmentAggregators can be in.
 */
enum AggregatorState {
    /**
     * The SegmentAggregator is not yet initialized.
     */
    NotInitialized,

    /**
     * Normal operation mode. The SegmentAggregator is accepting new StorageOperations and flushing them normally to Storage.
     */
    Writing,

    /**
     * A disagreement has just been detected between the currently-known state of the Segment and what Storage indicates
     * and no Reconciliation steps have been taken yet to resolve it.
     */
    ReconciliationNeeded,

    /**
     * A disagreement has been detected between the currently-known state of the Segment and what Storage indicates, and
     * the SegmentAggregator is currently attempting to reconcile the two until it detects a convergence or an unrecoverable situation.
     */
    Reconciling,

    /**
     * The SegmentAggregator is closed; no further operation is allowed on it.
     */
    Closed
}