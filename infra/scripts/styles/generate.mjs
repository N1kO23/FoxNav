// Generates the map style templates in infra/styles/ from the Protomaps basemap.
// `{{origin}}` is replaced by the FoxNav server with the origin each client used.
//
//   cd infra/scripts/styles && npm install && npm run generate
//
// These are starting points: the FoxNav look (design tokens) gets applied on top later.
import { writeFileSync } from "node:fs";
import { layers, namedFlavor } from "@protomaps/basemaps";

const ORIGIN = "{{origin}}";
const LANGUAGE = "en"; // labels fall back to local names where no English name exists

// FoxNav theme -> Protomaps flavor
const THEMES = { night: "dark", day: "light" };

for (const [theme, flavor] of Object.entries(THEMES)) {
  const style = {
    version: 8,
    name: `FoxNav ${theme}`,
    glyphs: `${ORIGIN}/assets/fonts/{fontstack}/{range}.pbf`,
    sprite: `${ORIGIN}/assets/sprites/v4/${flavor}`,
    sources: {
      protomaps: {
        type: "vector",
        url: `pmtiles://${ORIGIN}/tiles/region.pmtiles`,
        attribution:
          '<a href="https://protomaps.com">Protomaps</a> © <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>',
      },
    },
    layers: layers("protomaps", namedFlavor(flavor), { lang: LANGUAGE }),
  };
  const path = new URL(`../../styles/${theme}.json`, import.meta.url);
  writeFileSync(path, JSON.stringify(style, null, 2) + "\n");
  console.log(`wrote ${path.pathname} (${style.layers.length} layers)`);
}
