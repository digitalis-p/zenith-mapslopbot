// Generates PNG-to-NBT "Suppress (2-Layer)" CrubTech exports headlessly, the way Index.tsx does with the
// CrubTech preset and the CrubTech checkbox on (the colour-suppression test fixtures, src/test/resources/fixtures/png2nbt).
// Setup: git clone https://github.com/EvModder/PNG-to-NBT P2N   (fixtures made at commit a43dfb0)
//        import_map.json: { "imports": { "@/": "<P2N>/website/src/" } }
// Usage (deno 2, no network, writes only OUTDIR):
//   deno run --sloppy-imports --import-map=import_map.json --allow-read=<P2N>,. --allow-write=OUTDIR gen.ts \
//     OUTDIR NAME VERSION PATTERN SEED [SUPPORT] [minY]
// VERSION: 1.21.4 | 26.2 | 26.3   PATTERN: full | small | stripes   SUPPORT: none | steps
// Writes OUTDIR/NAME-suppress_crubtech.nbt and OUTDIR/NAME.grid.txt (the source colour grid). analyze.py prints a file's
// layers: python3 -I analyze.py <repo>/testserver FILE
import { BuildMode, SuppressStepDirection } from "@/types/conversion";
import { Shade, type ColorGrid, type ShadedColorRef } from "@/types/color";
import { SupportMode } from "@/types/ui";
import { getBuiltinPreset } from "@/data/presets";
import { TRANSPARENT_COLOR, MAP_SIZE } from "@/utils/color";
import {
  DEFAULT_SUPPORT_FILLER_BLOCK,
  DEFAULT_SHADE_FILLER_BLOCK,
  DEFAULT_CRUBTECH_SHADE_BREAKABLE_FILLER_BLOCK,
  DEFAULT_CRUBTECH_SHADE_PUSHABLE_FILLER_BLOCK,
  DEFAULT_SUPPRESS_2LAYER_LATE_FILLER_BLOCK,
  DEFAULT_DOMINATE_VOID_SHADE_FILLER_BLOCK,
  DEFAULT_RECESSIVE_VOID_SHADE_FILLER_BLOCK,
  DEFAULT_CRUBTECH_LAYER_GAP,
  DEFAULT_SUPPRESS_STEP_DIRECTION,
} from "@/data/defaultSettings";
import { createFillerAssignments } from "@/lib/fillerRules";
import { generateShapeMap } from "@/lib/shapeGeneration";
import { convertToNbt } from "@/lib/nbtExport";
import { requiresTwoLayerLateShading } from "@/lib/colorGridAnalysis";

const [outDir, name, version, pattern, seedArg, supportArg] = Deno.args;
let seed = Number(seedArg ?? "1") >>> 0;
function rnd(): number {
  seed = (seed * 1664525 + 1013904223) >>> 0;
  return seed / 4294967296;
}
const SHADES = [Shade.Dark, Shade.Flat, Shade.Light];
function col(id: number, shade: Shade): ShadedColorRef {
  return { id, isCustom: false, shade };
}

// colorGrid[x][z]
const grid: ColorGrid = Array.from({ length: MAP_SIZE }, () => Array<ShadedColorRef>(MAP_SIZE).fill(TRANSPARENT_COLOR));
// white, red, blue, black concrete; small art also uses lime and yellow concrete
const full = [8, 28, 25, 29, 19, 18, 36, 15, 23, 13, 26, 21];
const small = [8, 28, 25, 29];
for (let x = 0; x < MAP_SIZE; x++) {
  for (let z = 0; z < MAP_SIZE; z++) {
    if (pattern === "full") {
      grid[x][z] = col(full[Math.floor(rnd() * full.length)], SHADES[Math.floor(rnd() * 3)]);
    } else if (pattern === "small") {
      if (x >= 48 && x < 80 && z < 16) grid[x][z] = col(small[Math.floor(rnd() * small.length)], SHADES[Math.floor(rnd() * 3)]);
    } else if (pattern === "bamboo") {
      // 16 x 8 pixels with colour 7 (PLANT: the preset's bamboo_block[axis=x]) and white concrete
      if (x >= 88 && x < 104 && z < 8) grid[x][z] = col(rnd() < 0.5 ? 7 : 8, SHADES[Math.floor(rnd() * 3)]);
    } else if (pattern === "stripes") {
      grid[x][z] = col(small[(x >> 2) % small.length], SHADES[(z >> 1) % 3]);
    }
  }
}

const preset = getBuiltinPreset("CrubTech")!;
const support = supportArg === "steps" ? SupportMode.Steps : SupportMode.None;
let hasTransparency = false;
for (let x = 0; x < MAP_SIZE; x++) for (let z = 0; z < MAP_SIZE; z++) if (grid[x][z] === TRANSPARENT_COLOR) hasTransparency = true;
const late = requiresTwoLayerLateShading(grid, undefined);
const shapeMap = generateShapeMap(grid, false, false, hasTransparency, late, {
  layerGap: DEFAULT_CRUBTECH_LAYER_GAP,
  mixSteps: false,
  includeTransparentBlocks: false,
  paletteSeed: 0,
  waterSetting: undefined,
  enableWaterConvenience: support !== SupportMode.None,
  buildAtWorldMinY: Deno.args[6] === "minY",
  useCrubTech: true,
  skipEmptySuppressSteps: true,
  selectedMode: BuildMode.Suppress2Layer,
  selectedStepDirection: DEFAULT_SUPPRESS_STEP_DIRECTION as SuppressStepDirection,
} as never);
const shape = shapeMap[BuildMode.Suppress2Layer];
if (!shape) throw new Error("no Suppress2Layer shape (late shading needed: " + late + ")");
const fillers = createFillerAssignments(
  DEFAULT_SUPPORT_FILLER_BLOCK,
  DEFAULT_SHADE_FILLER_BLOCK,
  DEFAULT_CRUBTECH_SHADE_BREAKABLE_FILLER_BLOCK,
  DEFAULT_CRUBTECH_SHADE_PUSHABLE_FILLER_BLOCK,
  DEFAULT_SUPPRESS_2LAYER_LATE_FILLER_BLOCK,
  DEFAULT_DOMINATE_VOID_SHADE_FILLER_BLOCK,
  DEFAULT_RECESSIVE_VOID_SHADE_FILLER_BLOCK,
  support,
  true,
  false,
  false,
);
const out = await convertToNbt(shape, {
  minecraftVersion: version as never,
  selectedBlocks: preset.selectedBlocks,
  fillerAssignments: fillers,
  applySupportFloorYs: true,
  collapseDuplicatePaletteStates: true,
  forceXZ128: true,
  forceZ129: true,
  selectedBlocksCustom: {},
  customColors: [],
  baseName: name,
  buildMode: BuildMode.Suppress2Layer,
  suppressStepDirection: DEFAULT_SUPPRESS_STEP_DIRECTION as SuppressStepDirection,
  crubTech: true,
  markSuppressLoadSpotsInSchematic: false,
} as never);
const file = `${outDir}/${name}-suppress_crubtech${out.isZip ? ".zip" : ".nbt"}`;
await Deno.writeFile(file, out.data);
// The source colour grid, for checking the bot's expected-map renderer: one line per row z, "id:shade" per x.
const lines: string[] = [];
for (let z = 0; z < MAP_SIZE; z++) {
  const row: string[] = [];
  for (let x = 0; x < MAP_SIZE; x++) {
    const c = grid[x][z];
    row.push(c === TRANSPARENT_COLOR ? "-" : `${c.id}:${c.shade}`);
  }
  lines.push(row.join(" "));
}
await Deno.writeTextFile(`${outDir}/${name}.grid.txt`, lines.join("\n") + "\n");
console.log(file, out.data.length, "bytes; late shading:", late, "transparency:", hasTransparency);
