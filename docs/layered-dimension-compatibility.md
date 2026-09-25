# Layered dimension compatibility

WMap uses the `worldmap:wmap-layered-type` **dimension type** tag to select its
vertical cave-layer renderer. The bundled tag is at:

`src/main/resources/data/worldmap/tags/dimension_type/wmap-layered-type.json`

Vanilla Nether keeps its existing built-in behavior and is not included in the
tag. Modded dimensions use layered mapping only when their developer explicitly
adds a dimension type to this tag. To hardcode support for a mod dimension whose
owner does not add compatibility, add that dimension's **dimension type ID** to
the bundled file:

```json
{
  "replace": false,
  "values": [
    "examplemod:crystal_caves"
  ]
}
```

The value is the dimension type ID, which can differ from the dimension ID. If
the mod reuses one type for several dimensions, all of them are considered
layered. A dedicated type lets you target just one dimension.

Dimension mod authors can contribute entries to the same shared tag from their
mod JAR at:

`data/worldmap/tags/dimension_type/wmap-layered-type.json`

```json
{
  "replace": false,
  "values": ["examplemod:crystal_caves"]
}
```

The resource tag is merged by Minecraft, so each mod can add its own types
without replacing WMap's vanilla Nether entry. The tag data must be present on
both the server and clients in multiplayer.
