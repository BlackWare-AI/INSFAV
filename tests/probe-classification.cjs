/* Offline DOM fixtures; every request is intercepted. This is not a live Instagram test. */
const fs = require('node:fs'), assert = require('node:assert/strict');
const {chromium} = require('playwright');
(async () => {
 const browser = await chromium.launch({executablePath: process.env.CHROME_BIN, args: ['--no-sandbox', '--disable-gpu']});
 try {
  const page = await browser.newPage();
  const source = fs.readFileSync('app/src/main/assets/profile-probe.js', 'utf8');
  let body = '', requests = 0;
  await page.route('**/*', route => { requests++; return route.fulfill({contentType:'text/html; charset=utf-8', body}); });
  const check = async (html, reason) => {
   body = html; await page.goto('https://www.instagram.com/example/');
   const data = JSON.parse(await page.evaluate(s => eval('(' + s + ')')('example', '123', false), source));
   assert.equal(data.reason, reason); assert.equal(typeof data.pageReady, 'boolean'); assert.equal(typeof data.candidateFound, 'boolean');
  };
  await check('<body>Sorry, this page isn’t available.</body>', 'TEXT_MATCH');
  await check('<script type="application/json">{"rootView":{"resource":{"__dr":"PolarisErrorRoot.react"}}}</script>', 'ROUTED_ERROR');
  await check('<form id="loginForm"><input></form>', 'COOKIE_OR_LOGIN_GATE');
  await check('<link rel="canonical" href="https://www.instagram.com/other/">', 'CANONICAL_MISMATCH');
  await check('<link rel="canonical" href="https://www.instagram.com/example/">', 'NO_CANDIDATE');
  assert.equal(requests, 5);
  console.log('PASS 5 offline probe diagnostic fixtures; all requests intercepted');
 } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
