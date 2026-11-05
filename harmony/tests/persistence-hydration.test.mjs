// SPDX-License-Identifier: MIT
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import test from 'node:test';
const root=fileURLToPath(new URL('../../', import.meta.url)).replace(/\/$/, '');
const esbuild=createRequire(root+'/plugins/package.json')('esbuild');
const p=root+'/harmony/entry/src/main/ets/host/HostPersistence.ets';
const built=esbuild.buildSync({stdin:{contents:readFileSync(p,'utf8')+'\nexport {hydrateSession};',resolveDir:root+'/harmony/entry/src/main/ets/host',loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',loader:{'.ets':'ts'},resolveExtensions:['.ets','.ts','.js'],external:['@kit.*']});
let code=built.outputFiles[0].text;code=code.replace(/^import .*? from "@kit\..*?";$/gm, line=>line.includes('util')?'const util = {Base64Helper:class{},TextEncoder:class{},TextDecoder:class{}};':'');
const {hydrateSession}=await import('data:text/javascript;base64,'+Buffer.from(code).toString('base64'));
const assert=(await import('node:assert/strict')).default;
test('cold metadata hydration retains the cumulative artifact measurement base', () => {
  const session=hydrateSession({id:'session-1',artifactsFromMseq:'123',artifactsPhase:'ready',rows:[],artifacts:[]},[]);
  assert.equal(session.artifactsFromMseq,'123');
  assert.equal(session.artifactsPhase,'ready');
});
test('legacy or malformed artifact metadata does not invent a measurement start', () => {
  assert.equal(hydrateSession({id:'session-1',rows:[]},[]).artifactsFromMseq,undefined);
  assert.equal(hydrateSession({id:'session-1',rows:[],artifactsFromMseq:'-1'},[]).artifactsFromMseq,undefined);
});
