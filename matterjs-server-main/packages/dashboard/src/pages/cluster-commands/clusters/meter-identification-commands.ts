/**
 * @license
 * Copyright 2025-2026 Open Home Foundation
 * SPDX-License-Identifier: Apache-2.0
 */

import { css, html, nothing, type CSSResultGroup } from "lit";
import { customElement } from "lit/decorators.js";
import { METER_IDENTIFICATION_CLUSTER_ID, meterIdentificationInfo } from "../../../util/meter-identification.js";
import { BaseClusterCommands } from "../base-cluster-commands.js";
import { registerClusterCommands } from "../registry.js";

/**
 * Read-only decoding panel for the MeterIdentification cluster (ID: 0xB06 / 2822).
 */
@customElement("meter-identification-cluster-commands")
export class MeterIdentificationClusterCommands extends BaseClusterCommands {
    override render() {
        if (!this.node || this.cluster !== METER_IDENTIFICATION_CLUSTER_ID) return nothing;
        const info = meterIdentificationInfo(this.node.attributes, this.endpoint);
        if (!info.supported) return nothing;

        return html`
            <details class="command-panel" open>
                <summary>Meter Identification</summary>
                <div class="command-content">
                    <dl class="info-grid">
                        ${
                            info.meterType
                                ? html`<dt>Meter type</dt>
                                      <dd>${info.meterType}</dd>`
                                : nothing
                        }
                        ${
                            info.pointOfDelivery
                                ? html`<dt>Point of delivery</dt>
                                      <dd>${info.pointOfDelivery}</dd>`
                                : nothing
                        }
                        ${
                            info.meterSerialNumber
                                ? html`<dt>Serial number</dt>
                                      <dd>${info.meterSerialNumber}</dd>`
                                : nothing
                        }
                        ${
                            info.protocolVersion
                                ? html`<dt>Protocol version</dt>
                                      <dd>${info.protocolVersion}</dd>`
                                : nothing
                        }
                        ${
                            info.powerThresholdSupported && info.powerThreshold
                                ? html`
                                      ${
                                          info.powerThreshold.powerThresholdW !== undefined
                                              ? html`<dt>Power threshold</dt>
                                                    <dd>${info.powerThreshold.powerThresholdW} W</dd>`
                                              : nothing
                                      }
                                      ${
                                          info.powerThreshold.apparentPowerThresholdVA !== undefined
                                              ? html`<dt>Apparent power threshold</dt>
                                                    <dd>${info.powerThreshold.apparentPowerThresholdVA} VA</dd>`
                                              : nothing
                                      }
                                      ${
                                          info.powerThreshold.source
                                              ? html`<dt>Threshold source</dt>
                                                    <dd>${info.powerThreshold.source}</dd>`
                                              : nothing
                                      }
                                  `
                                : nothing
                        }
                    </dl>
                </div>
            </details>
        `;
    }

    static override styles: CSSResultGroup = [
        BaseClusterCommands.styles,
        css`
            .info-grid {
                display: grid;
                grid-template-columns: auto 1fr;
                gap: 6px 16px;
                margin: 0;
            }
            .info-grid dt {
                color: var(--text-color, rgba(0, 0, 0, 0.6));
                font-size: 13px;
            }
            .info-grid dd {
                margin: 0;
                font-weight: 500;
            }
        `,
    ];
}

registerClusterCommands(METER_IDENTIFICATION_CLUSTER_ID, "meter-identification-cluster-commands", {
    renderWhenOffline: true,
});

declare global {
    interface HTMLElementTagNameMap {
        "meter-identification-cluster-commands": MeterIdentificationClusterCommands;
    }
}
