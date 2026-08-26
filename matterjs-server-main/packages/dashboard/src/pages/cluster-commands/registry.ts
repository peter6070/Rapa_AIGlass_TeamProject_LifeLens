/**
 * @license
 * Copyright 2025-2026 Open Home Foundation
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Registry for cluster-specific command panel components.
 * Components register themselves by cluster ID and are dynamically
 * rendered in the cluster view when viewing that cluster.
 */

export interface ClusterCommandsRegistration {
    tagName: string;
    /**
     * Whether the panel stays visible while the node is unreachable. Set it for panels that only decode
     * cached attributes, or whose commands target a sleeping device; the cluster view hides the rest.
     */
    renderWhenOffline?: boolean;
}

const clusterCommandRegistry = new Map<number, ClusterCommandsRegistration>();

/**
 * Register a cluster command panel component.
 * @param clusterId - The Matter cluster ID (e.g., 6 for OnOff, 8 for LevelControl)
 * @param tagName - The custom element tag name (e.g., "on-off-cluster-commands")
 * @param options - Rendering behaviour the cluster view needs before it creates the element
 */
export function registerClusterCommands(
    clusterId: number,
    tagName: string,
    options: Omit<ClusterCommandsRegistration, "tagName"> = {},
): void {
    clusterCommandRegistry.set(clusterId, { tagName, ...options });
}

/**
 * Get the registered component tag name for a cluster ID.
 * @param clusterId - The Matter cluster ID
 * @returns The custom element tag name, or undefined if not registered
 */
export function getClusterCommandsTag(clusterId: number): string | undefined {
    return clusterCommandRegistry.get(clusterId)?.tagName;
}

/**
 * Check if a cluster has a registered command panel.
 * @param clusterId - The Matter cluster ID
 * @returns true if a command panel is registered
 */
export function hasClusterCommands(clusterId: number): boolean {
    return clusterCommandRegistry.has(clusterId);
}

/**
 * Whether a cluster's panel is shown while the node is unreachable.
 * @param clusterId - The Matter cluster ID
 * @returns true when the panel declared itself usable offline
 */
export function rendersClusterCommandsWhenOffline(clusterId: number): boolean {
    return clusterCommandRegistry.get(clusterId)?.renderWhenOffline === true;
}
