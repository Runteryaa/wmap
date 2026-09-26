# Layered dimension compatibility

Dimension type tags are not used by WMap. A mod opts in by shipping one small
JSON file in its own namespace:

`src/main/resources/data/<modid>/wmap_layered_dimensions.json`

The file is a JSON array. Simple entries can contain dimension IDs:

```json
[
  "examplemod:crystal_caves",
  "examplemod:another_cave_dimension"
]
```

If a dimension has a bedrock ceiling below its build-height limit, use an
object entry and set `max_layer_y` to the highest 8-block slice that should be
mapped. Slices above that height are omitted from the layer selector and
scanning range:

```json
[
  "examplemod:crystal_caves",
  {
    "id": "examplemod:bedrock_roof_caves",
    "max_layer_y": 120
  }
]
```

The field is optional. Without it, WMap allows layers up to the dimension's
normal build-height limit. Existing string-only files remain supported.

WMap checks every installed Fabric mod for
`data/<modid>/wmap_layered_dimensions.json` and combines the listed IDs. It
does not inspect dimension type IDs. The dimensions must be declared on both
the server and clients for multiplayer map scanning and synchronization to
agree.

WMap uses the same format in
`src/main/resources/data/worldmap/wmap_layered_dimensions.json`. It sets
`minecraft:the_nether`'s maximum Y to 127, the final block below the vanilla
Nether's bedrock ceiling. Since WMap layers are aligned to 8-block steps, the
highest selectable slice at or below that ceiling is Y=120; the next slice,
Y=128, is omitted. Other dimensions are not capped unless their declaration
includes `max_layer_y`.
