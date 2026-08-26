"""Tests for matter_server.client.client.MatterClient."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from chip.clusters import Objects as clusters
from matter_server.client.client import MatterClient
from matter_server.common.models import APICommand


async def test_write_attribute_sends_tag_keyed_value() -> None:
    """write_attribute must route struct values through dataclass_to_tag_dict.

    send_command must receive TLV-tag keys ("0".."5"), not field names, or the
    server rejects the write with INVALID_DATA_TYPE.
    """
    client = MatterClient(ws_server_url="ws://example.invalid/ws", aiohttp_session=MagicMock())
    client.send_command = AsyncMock(return_value=None)

    preset = clusters.Thermostat.Structs.PresetStruct(
        presetHandle=b"\x01",
        presetScenario=clusters.Thermostat.Enums.PresetScenarioEnum.kOccupied,
        name=None,
        coolingSetpoint=2500,
        heatingSetpoint=2100,
        builtIn=True,
    )

    await client.write_attribute(node_id=1, attribute_path="1/513/80", value=preset)

    client.send_command.assert_awaited_once_with(
        APICommand.WRITE_ATTRIBUTE,
        require_schema=4,
        node_id=1,
        attribute_path="1/513/80",
        value={
            "0": b"\x01",
            "1": clusters.Thermostat.Enums.PresetScenarioEnum.kOccupied,
            "3": 2500,
            "4": 2100,
            "5": True,
        },
    )
