// Generates the map style templates in infra/styles/ from the Protomaps basemap, recoloured
// with the FoxNav design tokens (design/tokens.json).
// `{{origin}}` is replaced by the FoxNav server with the origin each client used.
//
//   cd infra/scripts/styles && npm install && npm run generate
import { readFileSync, writeFileSync } from "node:fs";
import { layers, namedFlavor } from "@protomaps/basemaps";

const ORIGIN = "{{origin}}";
const LANGUAGE = "en"; // labels fall back to local names where no English name exists

const tokens = JSON.parse(readFileSync(new URL("../../../design/tokens.json", import.meta.url)));

// FoxNav theme -> the Protomaps flavor it starts from (for POI and landcover details).
const THEMES = { night: "dark", day: "light" };

// Which token colours each group of Protomaps flavor keys. Land use is flattened into the land
// colour for the clean "car navi" look: roads, water, parks and labels carry the map.
const GROUPS = {
  "map-land": ["background", "earth", "hospital", "industrial", "school", "pedestrian", "glacier", "sand",
    "beach", "aerodrome", "military"],
  "map-park": ["park_a", "park_b", "wood_a", "wood_b", "scrub_a", "scrub_b", "zoo"],
  "map-water": ["water"],
  "map-building": ["buildings"],
  "map-road": ["other", "minor_service", "minor_a", "minor_b", "link", "runway", "pier", "bridges_other",
    "bridges_minor", "bridges_link", "tunnel_other", "tunnel_minor", "tunnel_link"],
  "map-road-major": ["major", "bridges_major", "tunnel_major"],
  "map-road-highway": ["highway", "bridges_highway", "tunnel_highway"],
  "map-road-casing": ["minor_service_casing", "minor_casing", "link_casing", "major_casing_late",
    "highway_casing_late", "major_casing_early", "highway_casing_early", "bridges_other_casing",
    "bridges_minor_casing", "bridges_link_casing", "bridges_major_casing", "bridges_highway_casing",
    "tunnel_other_casing", "tunnel_minor_casing", "tunnel_link_casing", "tunnel_major_casing",
    "tunnel_highway_casing"],
  "map-railway": ["railway"],
  "map-boundary": ["boundaries"],
  "map-label": ["roads_label_minor", "roads_label_major", "subplace_label", "address_label", "state_label",
    "ocean_label"],
  "map-label-major": ["city_label", "country_label"],
  "map-label-halo": ["roads_label_minor_halo", "roads_label_major_halo", "subplace_label_halo",
    "city_label_halo", "state_label_halo", "address_label_halo"],
};

// Points of interest worth seeing from a car: landmarks you navigate by, not every café.
const DRIVER_POIS = new Set([
  "aerodrome", "ferry_terminal", "station", "marina", "peak", "park", "forest", "beach", "zoo",
  "stadium", "attraction", "museum", "university",
]);

/** Narrows the `pois` layer's ["in", ["get", "kind"], ["literal", [...]]] filter to DRIVER_POIS. */
function thinPois(layers) {
  const poiLayer = layers.find((layer) => layer.id === "pois");
  const kindFilter = poiLayer?.filter?.find((part) => Array.isArray(part) && part[0] === "in");
  const literal = kindFilter?.[2];
  if (!Array.isArray(literal) || literal[0] !== "literal") throw new Error("Unexpected pois filter in the Protomaps style");
  literal[1] = literal[1].filter((kind) => DRIVER_POIS.has(kind));
  return layers;
}

for (const [theme, base] of Object.entries(THEMES)) {
  const colors = tokens.color[theme];
  const flavor = { ...namedFlavor(base) };
  for (const [token, keys] of Object.entries(GROUPS)) {
    const value = colors[token]?.$value;
    if (!value) throw new Error(`design/tokens.json has no color.${theme}.${token}`);
    for (const key of keys) {
      if (!(key in flavor)) throw new Error(`Protomaps flavor has no key ${key}`);
      flavor[key] = value;
    }
  }

  const style = {
    version: 8,
    name: `FoxNav ${theme}`,
    glyphs: `${ORIGIN}/assets/fonts/{fontstack}/{range}.pbf`,
    sprite: `${ORIGIN}/assets/sprites/v4/${base}`,
    sources: {
      // Plain tile URLs rather than pmtiles://, so map apps can cache and download them offline.
      protomaps: {
        type: "vector",
        tiles: [`${ORIGIN}/tiles/region/{z}/{x}/{y}.mvt`],
        minzoom: 0,
        maxzoom: 15,
        attribution:
          '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>',
      },
    },
    layers: thinPois(layers("protomaps", flavor, { lang: LANGUAGE })),
  };
  const path = new URL(`../../styles/${theme}.json`, import.meta.url);
  writeFileSync(path, JSON.stringify(style, null, 2) + "\n");
  console.log(`wrote ${path.pathname} (${style.layers.length} layers)`);
}
