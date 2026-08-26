/**
 * @license
 * Copyright 2025-2026 Open Home Foundation
 * SPDX-License-Identifier: Apache-2.0
 */

import {
    getClusterCommandsTag,
    hasClusterCommands,
    registerClusterCommands,
    rendersClusterCommandsWhenOffline,
} from "../src/pages/cluster-commands/registry.js";

/** Out of the Matter cluster id range, so the real panel registrations stay untouched. */
const ONLINE_ONLY_CLUSTER = 0xf000_0001;
const OFFLINE_CAPABLE_CLUSTER = 0xf000_0002;

describe("cluster commands registry", () => {
    before(() => {
        registerClusterCommands(ONLINE_ONLY_CLUSTER, "online-only-cluster-commands");
        registerClusterCommands(OFFLINE_CAPABLE_CLUSTER, "offline-capable-cluster-commands", {
            renderWhenOffline: true,
        });
    });

    it("resolves a registered panel's tag name", () => {
        expect(getClusterCommandsTag(ONLINE_ONLY_CLUSTER)).to.equal("online-only-cluster-commands");
        expect(hasClusterCommands(ONLINE_ONLY_CLUSTER)).to.equal(true);
    });

    it("reports nothing for a cluster without a panel", () => {
        expect(getClusterCommandsTag(0xf000_00ff)).to.equal(undefined);
        expect(hasClusterCommands(0xf000_00ff)).to.equal(false);
        expect(rendersClusterCommandsWhenOffline(0xf000_00ff)).to.equal(false);
    });

    it("hides a panel offline unless it declares otherwise", () => {
        expect(rendersClusterCommandsWhenOffline(ONLINE_ONLY_CLUSTER)).to.equal(false);
        expect(rendersClusterCommandsWhenOffline(OFFLINE_CAPABLE_CLUSTER)).to.equal(true);
    });

    it("replaces a re-registered panel wholesale, dropping its previous options", () => {
        registerClusterCommands(OFFLINE_CAPABLE_CLUSTER, "replacement-cluster-commands");
        expect(getClusterCommandsTag(OFFLINE_CAPABLE_CLUSTER)).to.equal("replacement-cluster-commands");
        expect(rendersClusterCommandsWhenOffline(OFFLINE_CAPABLE_CLUSTER)).to.equal(false);
    });
});
