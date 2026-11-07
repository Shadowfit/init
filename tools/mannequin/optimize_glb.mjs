import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { quantize, prune } from '@gltf-transform/functions';
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS);
const doc = await io.read(process.argv[2]);
for (const mesh of doc.getRoot().listMeshes()) for (const prim of mesh.listPrimitives())
  for (const sem of prim.listSemantics()) if (sem.startsWith('COLOR_')) prim.setAttribute(sem, null);
await doc.transform(prune(), quantize({ quantizePosition: 14, quantizeNormal: 10, quantizeWeight: 8, quantizeGeneric: 8, quantizeColor: 8 }));
await io.write(process.argv[3], doc);
