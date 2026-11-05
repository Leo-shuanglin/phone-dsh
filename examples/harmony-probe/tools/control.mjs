#!/usr/bin/env node
// SPDX-License-Identifier: MIT
import { createRequire } from 'node:module'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mkdirSync, writeFileSync } from 'node:fs'
import { parseArgs } from 'node:util'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const require = createRequire(resolve(root, 'plugins/package.json'))
const { chromium } = require('playwright-core')
const { values } = parseArgs({ options: {
  url: { type: 'string', default: 'http://127.0.0.1:18765/probe/index.html?autorun=1' },
  evidence: { type: 'string', default: resolve(root, '.audit/harmony-probe/control') },
  chrome: { type: 'string', default: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' },
} })
mkdirSync(values.evidence, { recursive: true })
const browser = await chromium.launch({ executablePath: values.chrome, headless: true })
try {
  const page = await browser.newPage({ viewport: { width: 430, height: 932 } })
  await page.goto(values.url)
  await page.waitForFunction(() => ['pass', 'fail'].includes(globalThis.__PDSH_PROBE_RESULT__?.status), null, { timeout: 260_000 })
  const result = await page.evaluate(() => globalThis.__PDSH_PROBE_RESULT__)
  writeFileSync(resolve(values.evidence, 'desktop-page.json'), JSON.stringify(result, null, 2) + '\n')
  await page.screenshot({ path: resolve(values.evidence, 'desktop-control.png'), fullPage: true })
  console.log(JSON.stringify({ status: result.status, environment: 'desktop-control-only', stages: result.stages.map(stage => ({ stage: stage.stage, status: stage.status })) }))
  if (result.status !== 'pass') process.exitCode = 1
} finally { await browser.close() }
