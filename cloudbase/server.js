'use strict';

const {createServer} = require('node:http');
const {createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID, timingSafeEqual} = require('node:crypto');
const {readFileSync, existsSync} = require('node:fs');
const {join} = require('node:path');
const {database} = require('./database');

const TOKEN_FORMAT = /^[A-Za-z0-9_-]{32,128}$/;
const SESSION_MILLIS = 30 * 60_000;
const COMMAND_MILLIS = 10_000;
const RECEIPT_MILLIS = 20_000;
const MAX_BODY_BYTES = 256 * 1024;
const actions = new Set(['state', 'rules', 'guard', 'extra', 'favorites', 'wallpaper',
  'questions/reset', 'questions/source', 'questions/delete'].map((action) => '/api/' + action));
const tvErrors = new Set([400, 404, 409, 429, 503]);
const parentToken = process.env.STUDYTV_PARENT_TOKEN;
const deviceToken = process.env.STUDYTV_DEVICE_TOKEN;
if (!TOKEN_FORMAT.test(parentToken) || !TOKEN_FORMAT.test(deviceToken) || parentToken === deviceToken) {
  throw new Error('Configure distinct STUDYTV_PARENT_TOKEN and STUDYTV_DEVICE_TOKEN values (32–128 URL-safe characters).');
}

const digest = (value) => createHash('sha256').update(value).digest();
const parentKey = digest(parentToken);
const relayKey = digest('StudyTV relay ' + parentToken + ' ' + deviceToken);
const {CLOUDBASE_ENV_ID: env, CLOUDBASE_REGION: region, CLOUDBASE_APIKEY: apiKey,
  STUDYTV_WATCH_USER: watchUser, STUDYTV_WATCH_PASSWORD: watchPassword} = process.env;
if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(env || '') || !/^[a-z0-9-]+$/.test(region || '')
    || !apiKey || !watchUser || !watchPassword) {
  throw new Error('Configure the CloudBase environment, API key and read-only TV account.');
}
const gateway = `https://${env}.api.tcloudbasegateway.com`;
const db = database(gateway, apiKey);
const publicOrigin = process.env.STUDYTV_PUBLIC_ORIGIN;
const deviceRoutes = new Set(['/relay/setup', '/relay/claim', '/relay/result']);
const deviceKey = digest(deviceToken);
const matches = (value, key) => typeof value === 'string' && timingSafeEqual(digest(value), key);
const jsonObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const bundledAssets = join(__dirname, 'admin');
const assets = existsSync(bundledAssets) ? bundledAssets : join(__dirname, '../app/src/main/assets/admin');
const pages = new Map([
  ['/', ['text/html', readFileSync(join(assets, 'index.html'), 'utf8')
    .replace('<body>', '<body data-management="cloud">')
    .replace(/<!-- LAN_ONLY_START -->[\s\S]*?<!-- LAN_ONLY_END -->/g, '')]],
  ['/admin.css', ['text/css', readFileSync(join(assets, 'admin.css'))]],
  ['/admin.js', ['text/javascript', readFileSync(join(assets, 'admin.js'))]]
]);

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function send(res, status, data = '', type = 'text/plain') {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, {
    'Content-Type': type + '; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'",
    'Strict-Transport-Security': 'max-age=31536000',
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer'
  });
  res.end(data);
}

function sendJson(res, data, status = 200) {
  send(res, status, JSON.stringify(data), 'application/json');
}

async function readJson(req) {
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || '')) {
    throw new HttpError(400, '请求需要 JSON 数据');
  }
  if (Number(req.headers['content-length']) > MAX_BODY_BYTES) throw new HttpError(413, '请求过大');
  const chunks = [];
  let size = 0;
  for await (const chunk of req.iterator({destroyOnReturn: false})) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new HttpError(413, '请求过大');
    chunks.push(chunk);
  }
  let data;
  try {
    data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new HttpError(400, '设置格式不正确');
  }
  if (!jsonObject(data)) throw new HttpError(400, '设置格式不正确');
  return data;
}

function seal(data) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', relayKey, iv);
  return Buffer.concat([iv, cipher.update(JSON.stringify(data)), cipher.final(), cipher.getAuthTag()])
    .toString('base64');
}

function unseal(encoded) {
  const bytes = Buffer.from(encoded, 'base64');
  const decipher = createDecipheriv('aes-256-gcm', relayKey, bytes.subarray(0, 12));
  decipher.setAuthTag(bytes.subarray(-16));
  return JSON.parse(Buffer.concat([decipher.update(bytes.subarray(12, -16)), decipher.final()]));
}

const expires = (millis) => new Date(Date.now() + millis).toISOString();
const remaining = (record) => Math.max(0, Date.parse(record.until) - Date.now());
const sessionKey = (authorization) => digest('StudyTV session ' + parentToken + ' ' + authorization);
// Reset the upload marker on documents created by the previous deployment.
const idle = {phase: 'idle', id: '', owner: '', until: '', request: '', receipt: '', importing: false};

async function issue(res, path, body, owner) {
  const slot = await db.read('tv');
  if (!slot.importing && slot.phase !== 'idle' && remaining(slot) > 0) {
    throw new HttpError(409, '电视正在处理另一项操作，请稍后重试。');
  }
  const id = randomUUID();
  if (!await db.update('tv', {revision: slot.revision}, {
    ...idle, phase: 'queued', id, owner, until: expires(COMMAND_MILLIS),
    request: seal({path, body})
  })) throw new HttpError(409, '电视正在处理另一项操作，请稍后重试。');
  sendJson(res, {jobId: id, waitMillis: COMMAND_MILLIS + 1000}, 202);
}

async function deviceRequest(req, res) {
  if (!matches(req.headers['x-studytv-device'], deviceKey)) {
    throw new HttpError(401, '电视令牌不正确');
  }
  const input = await readJson(req);
  if (req.url === '/relay/setup') {
    const response = await fetch(gateway + '/auth/v1/token', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({grant_type: 'password', username: watchUser, password: watchPassword}),
      redirect: 'error',
      signal: AbortSignal.timeout(4500)
    });
    const auth = await response.json();
    if (!response.ok || !auth.refresh_token || !auth.access_token) {
      throw new HttpError(502, 'CloudBase 电视订阅账号验证失败');
    }
    sendJson(res, {env, region, access_token: auth.access_token,
      refresh_token: auth.refresh_token, expires_in: auth.expires_in});
    return;
  }
  const slot = await db.read('tv');
  if (slot.importing || !input.id || input.id !== slot.id || !(remaining(slot) > 0)) {
    sendJson(res, {});
    return;
  }
  if (req.url === '/relay/claim') {
    if (slot.phase !== 'queued') {
      sendJson(res, {});
      return;
    }
    const {path, body} = unseal(slot.request);
    if (!actions.has(path) || !await db.update('tv', {revision: slot.revision, until: {$gt: expires(0)}},
      {phase: 'claimed', request: ''})) {
      sendJson(res, {});
      return;
    }
    sendJson(res, {id: slot.id, path, body, leaseMillis: remaining(slot)});
    return;
  }
  if (slot.phase === 'claimed') {
    const {status, data, message} = input;
    if (!(status === 200 && jsonObject(data)) && !(tvErrors.has(status) && typeof message === 'string')) {
      throw new HttpError(400, '电视回执格式不正确');
    }
    await db.update('tv', {revision: slot.revision, until: {$gt: expires(0)}},
      {phase: 'done', until: expires(RECEIPT_MILLIS), receipt: seal({status, data, message})});
  }
  sendJson(res, {});
}

async function receipt(res, id, owner, session) {
  const slot = await db.read('tv');
  if (id && slot.id === id && slot.owner === owner && slot.importing) {
    throw new HttpError(404, '外网文件导入已停用');
  }
  if (!id || slot.id !== id || slot.owner !== owner || !(remaining(slot) > 0)) {
    const message = slot.id === id && slot.owner === owner && slot.phase === 'queued'
      ? '电视未领取操作。请确认电视开机、联网并已建立实时订阅。'
      : '电视未返回确认。请先刷新状态，确认结果后再操作。';
    throw new HttpError(504, message);
  }
  if (slot.phase !== 'done') {
    sendJson(res, {}, 202);
    return;
  }
  const result = unseal(slot.receipt);
  if (!await db.update('tv', {revision: slot.revision}, idle)) {
    throw new HttpError(409, '回执已读取，请刷新电视状态');
  }
  if (result.status !== 200) throw new HttpError(result.status, result.message);
  sendJson(res, {...result.data, expiresIn: remaining(session)});
}

async function handle(req, res) {
  const path = req.url;
  if (req.method === 'GET' && pages.has(path)) {
    const [type, body] = pages.get(path);
    send(res, 200, body, type);
    return;
  }
  if (req.method === 'POST' && deviceRoutes.has(path)) {
    await deviceRequest(req, res);
    return;
  }
  if (!path.startsWith('/api/')) throw new HttpError(404, '页面不存在');
  if (req.headers['x-studytv'] !== '1'
      || (req.headers['sec-fetch-site'] && req.headers['sec-fetch-site'] !== 'same-origin')
      || (publicOrigin && req.headers.origin && req.headers.origin !== publicOrigin)) {
    throw new HttpError(403, '请求来源无效');
  }
  const action = path.slice('/api/'.length);
  if (!['login', 'logout', 'receipt'].includes(action) && !actions.has(path)) {
    throw new HttpError(404, '设置入口不存在');
  }
  if (req.method !== (action === 'state' ? 'GET' : 'POST')) {
    throw new HttpError(405, '请求方式不正确');
  }
  if (action === 'login') {
    const {token} = await readJson(req);
    if (!matches(token, parentKey)) throw new HttpError(401, '家长令牌不正确');
    const bearer = randomBytes(32).toString('base64url');
    const key = sessionKey('Bearer ' + bearer).toString('hex');
    if (!await db.update('session', {}, {key, until: expires(SESSION_MILLIS)})) {
      throw new HttpError(503, '请初始化 CloudBase 会话文档');
    }
    sendJson(res, {token: bearer, expiresIn: SESSION_MILLIS});
    return;
  }
  const current = await db.read('session');
  if (typeof req.headers.authorization !== 'string' || !/^[0-9a-f]{64}$/.test(current.key)
      || !(remaining(current) > 0)
      || !timingSafeEqual(sessionKey(req.headers.authorization), Buffer.from(current.key, 'hex'))) {
    throw new HttpError(401, '登录已失效，请重新输入家长令牌');
  }
  if (action === 'logout') {
    await db.update('session', {revision: current.revision}, {key: '', until: ''});
    sendJson(res, {});
    return;
  }
  const body = action === 'state' ? {} : await readJson(req);
  if (action === 'receipt') await receipt(res, body.id, current.revision, current);
  else await issue(res, path, body, current.revision);
}

const server = createServer({requestTimeout: 15_000, headersTimeout: 10_000}, (req, res) => {
  handle(req, res).catch((error) => {
    if (error instanceof HttpError) send(res, error.status, error.message);
    else send(res, 500, '服务暂时无法处理请求');
  });
});
server.listen(Number(process.env.PORT || 9000), '0.0.0.0');
