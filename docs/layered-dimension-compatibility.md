# Layered dimension compatibility

Dimension type tags are not used by WMap. A mod opts in by shipping one small
JSON file in its own namespace:

`src/main/resources/data/<modid>/wmap_layered_dimensions.json`

The file is a JSON array containing dimension IDs only:

```json
[
  "examplemod:crystal_caves",
  "examplemod:another_cave_dimension"
]
```

WMap checks every installed Fabric mod for
`data/<modid>/wmap_layered_dimensions.json` and combines the listed IDs. It
does not inspect dimension type IDs. The dimensions must be declared on both
the server and clients for multiplayer map scanning and synchronization to
agree.

WMap uses the same format in
`src/main/resources/data/worldmap/wmap_layered_dimensions.json`, which lists
`minecraft:the_nether` so vanilla Nether keeps its layered map view.
