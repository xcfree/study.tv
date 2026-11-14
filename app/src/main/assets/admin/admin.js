'use strict';

const byId = (id) => document.getElementById(id);
const cloud = document.body.dataset.management === 'cloud';
const unlockPath = location.pathname;
let phoneUnlock = !cloud && unlockPath.startsWith('/unlock/');
const loginForm = byId('login-form');
const loginPin = byId('login-pin');
const consolePanel = byId('console');
const notice = byId('notice');
const rulesForm = byId('rules-form');
const wake = byId('wake');
const wallpaper = byId('wallpaper-select');
const extraMinutes = byId('extra-minutes');
const sessionKey = 'studytv-session';
const managementCopy = ['login-title', 'login-intro', 'login-action', 'login-hint']
  .map((id) => [byId(id), byId(id).textContent]);
let token = '';
let state;
let busy = false;
let expiryTimer;
let rulesDirty = false;
let favoritesDirty = false;
let favoriteDraft = [];
let questionDraft;
let questionsDirty = false;
const questionDays = ['默认科目', '周一', '周二', '周三', '周四', '周五', '周六', '周日'];

if (phoneUnlock) {
  byId('login-title').textContent = '验证电视上的操作';
  byId('login-intro').textContent = '在这里输入 6 位家长 PIN，完成电视上的验证。';
  byId('login-action').textContent = '验证 PIN';
  byId('login-hint').textContent = '验证地址在电视显示的 2 分钟内有效。';
} else {
  try { token = sessionStorage.getItem(sessionKey) || ''; } catch {}
}

if (cloud) {
  byId('management-mode').textContent = 'CloudBase 外网';
  byId('management-footer').textContent = '学习电视 · CloudBase 外网管理';
  byId('login-intro').textContent = '输入 CloudBase 服务配置的家长令牌，连接电视。';
  byId('login-hint').textContent = '电视开机联网且开启 CloudBase 管理时可以操作。';
  byId('login-label').textContent = '家长令牌';
  loginPin.inputMode = 'text';
  loginPin.autocapitalize = 'none';
  loginPin.spellcheck = false;
  loginPin.pattern = '[A-Za-z0-9_\\-]{32,128}';
  loginPin.maxLength = 128;
  loginPin.placeholder = '输入家长令牌';
}

function storeToken(value) {
  token = value;
  try {
    if (value) sessionStorage.setItem(sessionKey, value);
    else sessionStorage.removeItem(sessionKey);
  } catch {}
}

function showMessage(message, error = false) {
  if (consolePanel.hidden) {
    byId('login-message').textContent = message;
  } else {
    notice.textContent = message;
    notice.classList.toggle('error', error);
    notice.hidden = !message;
  }
}

function showUploadMessage(message, error = false) {
  const status = byId('upload-status');
  status.textContent = message;
  status.classList.toggle('error', error);
  status.hidden = !message;
}

function leave(message) {
  clearTimeout(expiryTimer);
  storeToken('');
  state = null;
  rulesDirty = favoritesDirty = questionsDirty = false;
  questionDraft = undefined;
  consolePanel.hidden = true;
  if (!cloud) showUploadMessage('');
  byId('logout').hidden = true;
  byId('login-panel').hidden = false;
  loginPin.value = '';
  showMessage(message, true);
  window.scrollTo(0, 0);
}

async function sendRequest(path, {json, file, subject, replaceQuestions} = {}) {
  const headers = {'X-StudyTV': '1'};
  if (token) headers.Authorization = 'Bearer ' + token;
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (file) {
    body = file;
    if (subject) headers['X-StudyTV-Subject'] = encodeURIComponent(subject);
    if (replaceQuestions !== undefined) headers['X-StudyTV-Replace-Questions'] = replaceQuestions ? '1' : '0';
  }
  let response;
  try {
    response = await fetch('/api/' + path, {method: body ? 'POST' : 'GET', headers, body});
  } catch {
    if (cloud) {
      throw new Error('与 CloudBase 的连接中断。请先刷新电视状态，确认结果后再操作。');
    }
    throw new Error(phoneUnlock
      ? '无法连接电视。请确认两台设备在同一网络，且电视仍显示手机验证页。'
      : '无法连接电视。请确认电视已开机，且两台设备在同一网络。');
  }
  if (!response.ok) {
    const error = new Error((await response.text()) || '请求失败，请重试。');
    error.status = response.status;
    throw error;
  }
  return {status: response.status, data: await response.json()};
}

async function request(path, options) {
  const result = await sendRequest(path, options);
  if (!cloud || result.status !== 202) return result.data;
  const {jobId, waitMillis} = result.data;
  const deadline = performance.now() + waitMillis + 2000;
  while (performance.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const receipt = await sendRequest('receipt', {json: {id: jobId}});
    if (receipt.status !== 202) return receipt.data;
  }
  throw new Error('电视未返回确认。请先刷新状态，确认结果后再操作。');
}

async function run(task, progress = '', report = showMessage) {
  if (busy) return;
  busy = true;
  (consolePanel.hidden ? loginForm : consolePanel).setAttribute('aria-busy', 'true');
  report(progress);
  try {
    await task();
  } catch (error) {
    if (error.status === 401 && token) leave(error.message);
    else report(error.message, true);
  } finally {
    busy = false;
    loginForm.removeAttribute('aria-busy');
    consolePanel.removeAttribute('aria-busy');
  }
}

function render(next) {
  state = next;
  byId('login-panel').hidden = true;
  consolePanel.hidden = false;
  byId('logout').hidden = false;
  byId('connection').textContent = state.connected ? '无障碍服务已连接' : '无障碍服务未连接';
  byId('used').textContent = state.used;
  byId('daily-left').textContent = state.dailyLeft;
  byId('available').textContent = state.available;
  renderHistory(state.viewingHistory);
  renderQuizHistory(state.quizHistory);
  byId('guard-toggle').textContent = state.enabled ? '关闭观看管控' : '开启观看管控';
  byId('extra').disabled = !state.enabled;
  extraMinutes.disabled = !state.enabled || !state.extraMax;
  extraMinutes.hidden = byId('extra-minutes-label').hidden = !state.extraMax;
  byId('extra').textContent = state.extraMax ? '增加时间' : '增加 15 分钟';
  byId('question-count').textContent = state.questionCount + ' 道题';
  renderQuestions();
  wallpaper.options[1].disabled = !state.hasImage;
  wallpaper.options[2].disabled = !state.hasVideo;
  wallpaper.value = state.wallpaper;
  if (!cloud) {
    const settings = state.cloudBase;
    const toggle = byId('cloud-toggle');
    toggle.disabled = !settings?.configured;
    toggle.textContent = settings?.enabled ? '关闭 CloudBase 控制台' : '开启 CloudBase 控制台';
    byId('cloud-status').textContent = settings?.configured ? settings.status : '请先在电视上保存连接设置';
  }
  renderRules();
  if (!favoritesDirty) favoriteDraft = [...state.favorites];
  renderFavorites();
  clearTimeout(expiryTimer);
  expiryTimer = setTimeout(() => leave(cloud ? '登录已到期，请重新输入家长令牌。'
    : '登录已到期，请重新输入家长 PIN。'), state.expiresIn);
}

function renderQuizHistory(days) {
  const filter = byId('quiz-stats-filter');
  const date = byId('quiz-stats-date');
  const message = byId('quiz-stats-message');
  const table = byId('quiz-stats-table');
  const body = table.tBodies[0];
  filter.hidden = !days?.length;
  if (filter.hidden) {
    date.onchange = null;
    date.replaceChildren();
    body.replaceChildren();
    table.hidden = true;
    message.hidden = false;
    message.textContent = '更新电视应用后可查看答题统计。';
    return;
  }

  const previous = date.selectedIndex > 0 ? date.value : '';
  date.replaceChildren(...days.map((day, index) => new Option(day.date + (index === 0 ? ' · 今天' : ''), day.date)));
  if (days.some((day) => day.date === previous)) date.value = previous;

  function showDay() {
    const {subjects} = days[date.selectedIndex];
    body.replaceChildren();
    table.hidden = subjects.length === 0;
    message.hidden = !table.hidden;
    message.textContent = table.hidden ? '当天暂无答题记录' : '';
    for (const {name, total, correct, accuracy} of subjects) {
      const row = body.insertRow();
      const subject = document.createElement('th');
      subject.scope = 'row';
      subject.textContent = name;
      row.append(subject);
      for (const value of [total, correct, accuracy]) row.insertCell().textContent = value;
    }
  }

  date.onchange = showDay;
  showDay();
}

function renderHistory(days) {
  const grid = byId('history-grid');
  const detail = byId('history-detail');
  const selectedDate = grid.querySelector('[aria-pressed="true"]')?.dataset.date;
  const restoreFocus = grid.contains(document.activeElement);
  const today = days[days.length - 1];
  byId('history-range').textContent = days[0].date + ' 至 ' + today.date + ' · 仅统计计入管控额度的观看时间';
  renderHistoryMonths(days);
  let selected;

  function select(button, day) {
    if (selected && selected !== button) {
      selected.tabIndex = -1;
      selected.setAttribute('aria-pressed', 'false');
    }
    selected = button;
    button.tabIndex = 0;
    button.setAttribute('aria-pressed', 'true');
    detail.textContent = day.description;
  }

  const buttons = days.map((day, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.date = day.date;
    button.dataset.level = day.level;
    button.title = day.description;
    button.setAttribute('aria-label', day.description);
    button.setAttribute('aria-pressed', 'false');
    button.tabIndex = -1;
    button.addEventListener('focus', () => select(button, day));
    button.addEventListener('click', () => select(button, day));
    button.addEventListener('keydown', (event) => {
      let next;
      if (event.key === 'ArrowLeft') next = index - 7;
      if (event.key === 'ArrowRight') next = index + 7;
      if (event.key === 'ArrowUp' && index % 7 !== 0) next = index - 1;
      if (event.key === 'ArrowDown' && index % 7 !== 6) next = index + 1;
      if (buttons[next]) {
        event.preventDefault();
        buttons[next].focus();
      }
    });
    return button;
  });
  const previous = days.findIndex((day) => day.date === selectedDate);
  const index = previous >= 0 ? previous : days.length - 1;
  grid.replaceChildren(...buttons);
  select(buttons[index], days[index]);
  if (restoreFocus) selected.focus();
}

function renderHistoryMonths(days) {
  const lastWeek = Math.floor((days.length - 1) / 7);
  const shortOpeningMonth = days[0].date.slice(5, 7) !== days[Math.min(13, days.length - 1)].date.slice(5, 7);
  const labels = [];
  let previousMonth;
  let previousWeek = -1;
  days.forEach(({date}, index) => {
    const month = date.slice(5, 7);
    if (month === previousMonth) return;
    const week = Math.max(Math.floor(index / 7), previousWeek + 1);
    previousMonth = month;
    previousWeek = week;
    const label = document.createElement('span');
    label.textContent = Number(month) + '月';
    label.style.gridColumn = week + 1;
    if ((week === 0 && shortOpeningMonth) || week === lastWeek) label.style.justifySelf = 'end';
    labels.push(label);
  });
  byId('history-months').replaceChildren(...labels);
}

function renderRules() {
  const fields = byId('rule-fields');
  if (fields.children.length === 0) {
    state.rules.forEach(({label}) => {
      const field = document.createElement('label');
      field.textContent = label;
      const input = document.createElement('input');
      input.type = 'number';
      input.min = '1';
      input.step = '1';
      input.required = true;
      field.append(input);
      fields.append(field);
    });
  }
  fields.querySelectorAll('input').forEach((input, index) => {
    input.max = state.rules[index].max;
    if (!rulesDirty) input.value = state.rules[index].value;
  });
  if (!rulesDirty) wake.checked = state.relockOnWake;
}

function renderFavorites() {
  const list = byId('favorites');
  const apps = new Map(state.apps.map((app) => [app.key, app]));
  const focused = document.activeElement?.dataset;
  list.replaceChildren();
  const fixedCount = state.studyTile ? 1 : 0;
  byId('pinned-count').textContent = '已固定 ' + (favoriteDraft.length + fixedCount) + ' 个';
  if (fixedCount) {
    const item = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = '01　' + state.studyTile + '（固定）';
    item.append(name);
    list.append(item);
  }
  const ordered = [...new Set([...favoriteDraft, ...apps.keys()])];
  ordered.forEach((key) => {
    if (!apps.has(key)) return;
    const index = favoriteDraft.indexOf(key);
    const label = apps.get(key).label;
    const item = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = (index < 0 ? '' : String(index + 1 + fixedCount).padStart(2, '0') + '　') + label;
    item.append(name);
    if (index >= 0) {
      addAction('前移', index === 0, () => move(index, -1));
      addAction('后移', index === favoriteDraft.length - 1, () => move(index, 1));
    }
    addAction(index < 0 ? '固定' : '移除', false, () => {
      if (index < 0) favoriteDraft.push(key);
      else favoriteDraft.splice(index, 1);
      renderDraft();
    });
    list.append(item);

    function addAction(text, disabled, action) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = text;
      button.disabled = disabled;
      button.dataset.key = key;
      button.dataset.action = text;
      button.setAttribute('aria-label', text + label);
      button.addEventListener('click', () => { if (!busy) action(); });
      item.append(button);
    }
  });
  if (focused?.key) {
    const buttons = Array.from(list.querySelectorAll('button:not(:disabled)'));
    const match = buttons.find((button) => button.dataset.key === focused.key && button.dataset.action === focused.action)
      || buttons.find((button) => button.dataset.key === focused.key);
    if (match) match.focus();
  }
}

function renderDraft() {
  favoritesDirty = true;
  renderFavorites();
}

function move(index, delta) {
  const [key] = favoriteDraft.splice(index, 1);
  favoriteDraft.splice(index + delta, 0, key);
  renderDraft();
}

function update(path, json, message) {
  return run(async () => {
    const next = await request(path, {json});
    if (path === 'rules') rulesDirty = false;
    if (path === 'favorites') favoritesDirty = false;
    if (path === 'questions/source' || path === 'questions/reset') questionsDirty = false;
    render(next);
    showMessage(message);
  }, '正在保存…');
}

function renderQuestions() {
  const subjects = state.questionSubjects;
  const legacy = byId('reset-questions');
  legacy.hidden = !!subjects;
  legacy.disabled = !state.customQuestions;
  byId('question-controls').hidden = !subjects;

  const current = subjects?.find(({source}) => source === state.questionSource);
  const label = state.customQuestions ? current?.name || (subjects ? '混合学科' : '自定义题库') : '内置题库';
  byId('question-source').textContent = '今日出题 · ' + (state.questionSourceLabel || label) + ' · ' + state.questionCount + ' 道';
  if (!subjects) return;

  byId('question-upgrade').hidden = !!state.questionSchedule;
  byId('question-schedule-editor').hidden = !state.questionSchedule;
  if (state.questionSchedule) {
    if (!questionsDirty) questionDraft = JSON.parse(JSON.stringify(state.questionSchedule));
    renderQuestionEditor();
  }
  byId('question-subject-summary').textContent = '已导入 ' + subjects.length + ' / 9 个学科';
  if (!cloud) {
    byId('question-subject-names').replaceChildren(...subjects.map(({name}) => new Option(name, name)));
  }
  byId('question-subjects').replaceChildren(...subjects.map(({name, count}) => {
    const row = document.createElement('li');
    const description = document.createElement('span');
    description.textContent = name + ' · ' + count + ' 道';
    row.append(description);
    if (!cloud) {
      const replace = document.createElement('button');
      replace.type = 'button';
      replace.textContent = '设为备用';
      replace.setAttribute('aria-label', '将' + name + '设为备用学科');
      replace.addEventListener('click', () => {
        byId('question-subject-name').value = name;
        byId('questions-file').focus();
      });
      row.append(replace);
    }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.textContent = '删除';
    remove.setAttribute('aria-label', '删除' + name + '题库');
    remove.addEventListener('click', () => {
      if (confirm('删除「' + name + '」题库？')) update('questions/delete', {subject: name}, '学科题库已删除');
    });
    row.append(remove);
    return row;
  }));
}

function questionSelection(day) {
  return day === 0 ? questionDraft.default : questionDraft.week[day - 1];
}

function setQuestionSelection(day, sources) {
  if (day === 0) questionDraft.default = sources;
  else questionDraft.week[day - 1] = sources;
  questionsDirty = true;
}

function questionLabel(source) {
  if (source === '') return '全部已导入学科';
  if (source === '@builtin') return '数学（内置题库）';
  return state.questionSubjects.find((subject) => subject.source === source)?.name || '已删除科目：' + source.slice(8);
}

function renderQuestionEditor() {
  const day = Number(byId('question-day').value);
  const selected = questionSelection(day);
  const inherits = selected === null;
  byId('question-inherit-label').hidden = day === 0;
  byId('question-inherit').checked = inherits;
  const sources = selected || questionDraft.default;
  const choices = byId('question-choices');
  choices.disabled = inherits;
  const available = [{source: '', name: state.questionSubjects.length
    ? '全部已导入学科（自动包含后续导入）' : '全部已导入学科（暂无导入，使用内置题库）'},
    {source: '@builtin', name: '数学（内置题库）', count: state.builtinQuestionCount}, ...state.questionSubjects];
  for (const source of sources) {
    if (!available.some((item) => item.source === source)) available.push({source, name: questionLabel(source)});
  }
  const focused = choices.contains(document.activeElement) ? document.activeElement.dataset.source : undefined;
  const legend = document.createElement('legend');
  legend.className = 'micro';
  legend.textContent = '选择出题科目';
  choices.replaceChildren(legend, ...available.map(({source, name, count}) => {
    const label = document.createElement('label');
    label.className = 'check';
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.dataset.source = source;
    input.checked = sources.includes(source);
    input.addEventListener('change', () => {
      const current = questionSelection(day) || questionDraft.default;
      if (busy) { input.checked = current.includes(source); return; }
      let next = current.filter((item) => item !== source);
      if (input.checked) next = source === '' ? [''] : [...next.filter((item) => item !== ''), source];
      setQuestionSelection(day, next);
      for (const checkbox of choices.querySelectorAll('input')) checkbox.checked = next.includes(checkbox.dataset.source);
      renderQuestionSummary();
    });
    label.append(input, document.createTextNode(name + (count === undefined ? '' : ' · ' + count + ' 道')));
    return label;
  }));
  if (focused !== undefined) Array.from(choices.querySelectorAll('input')).find((input) => input.dataset.source === focused)?.focus();
  renderQuestionSummary();
}

function renderQuestionSummary() {
  byId('question-draft-status').textContent = questionsDirty ? '有未保存的修改，切换日期后会保留，最后统一保存。' : '与电视端共用同一份设置。';
  byId('question-week-summary').replaceChildren(...questionDays.map((name, index) => {
    const row = document.createElement('li');
    const selection = questionSelection(index);
    row.textContent = name + (index === state.questionWeekday + 1 ? '（今天）' : '') + '：'
      + (selection === null ? '沿用默认 · ' : '')
      + ((selection || questionDraft.default).map(questionLabel).join('、') || '尚未选择');
    return row;
  }));
}

function upload(kind, input, maxMb, message, subject) {
  const file = input.files[0];
  if (!file || !file.size || file.size > maxMb * 1024 * 1024) {
    showUploadMessage(file?.size ? '文件超过 ' + maxMb + ' MB' : '请选择非空文件', true);
    return;
  }
  run(async () => {
    const path = 'import/' + kind;
    const questions = kind === 'questions';
    const options = {file, subject, replaceQuestions: questions ? false : undefined};
    let next;
    try {
      next = await request(path, options);
    } catch (error) {
      if (!questions || error.status !== 409) throw error;
      if (!confirm(error.message)) {
        showUploadMessage('已取消替换题库');
        return;
      }
      next = await request(path, {...options, replaceQuestions: true});
    }
    render(next);
    if (input.files[0] === file) input.value = '';
    const subjects = next.importedQuestionSubjects;
    showUploadMessage(subjects?.length ? '已导入「' + subjects.join('、') + '」题库' : message);
  }, '正在上传并验证文件，请保持电视管理页开启…', showUploadMessage);
}

loginForm.addEventListener('submit', (event) => {
  event.preventDefault();
  run(async () => {
    const credential = loginPin.value;
    loginPin.value = '';
    const next = await request(phoneUnlock ? unlockPath.slice(1) : 'login', {
      json: cloud ? {token: credential} : {pin: credential}
    });
    if (phoneUnlock && !next.token) {
      loginForm.hidden = true;
      byId('login-hint').hidden = true;
      showMessage('验证通过，请继续在电视上操作。');
      return;
    }
    if (phoneUnlock) {
      phoneUnlock = false;
      for (const [element, text] of managementCopy) element.textContent = text;
      try { history.replaceState(null, '', '/'); } catch {}
    }
    storeToken(next.token);
    render(cloud ? await request('state') : next);
    showMessage('');
    byId('refresh').focus();
  }, phoneUnlock ? '正在验证…' : '正在登录…');
});

byId('logout').addEventListener('click', () => run(async () => {
  await request('logout', {json: {}});
  leave(cloud ? '已退出。可以用家长令牌重新登录。' : '已退出。可以用家长 PIN 重新登录。');
}, '正在退出…'));

byId('refresh').addEventListener('click', () => run(async () => {
  render(await request('state'));
  showMessage('已刷新电视状态');
}, '正在刷新…'));

byId('guard-toggle').addEventListener('click', () => {
  update('guard', {enabled: !state.enabled}, state.enabled ? '观看管控已关闭' : '观看管控已开启');
});
byId('extra-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const minutes = state.extraMax ? extraMinutes.valueAsNumber : 15;
  update('extra', state.extraMax ? {minutes} : {}, '已增加 ' + minutes + ' 分钟');
});

rulesForm.addEventListener('input', () => { rulesDirty = true; });
rulesForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const values = Array.from(byId('rule-fields').querySelectorAll('input'), (input) => input.valueAsNumber);
  update('rules', {values, relockOnWake: wake.checked}, '观看规则已保存');
});
byId('save-favorites').addEventListener('click', () => update('favorites', {favorites: favoriteDraft}, '桌面常用应用已保存'));
byId('save-wallpaper').addEventListener('click', () => update('wallpaper', {wallpaper: wallpaper.value}, '电视背景已更新'));
byId('reset-questions').addEventListener('click', () => update('questions/reset', {}, '已恢复内置题库'));
byId('save-question-source').addEventListener('click', () => {
  run(async () => {
    const selections = [questionDraft.default, ...questionDraft.week];
    const empty = selections.findIndex((selection) => selection !== null && selection.length === 0);
    if (empty >= 0) throw new Error(questionDays[empty] + '：请至少选择一个出题科目');
    const next = await request('questions/source', {json: {schedule: questionDraft}});
    questionsDirty = false;
    render(next);
    showMessage('出题科目及每周安排已保存');
  }, '正在保存…', (message, error = false) => {
    byId('question-error').textContent = error ? message : '';
    byId('question-error').hidden = !error;
    showMessage(message, error);
  });
});
byId('question-day').addEventListener('change', renderQuestionEditor);
byId('question-inherit').addEventListener('change', () => {
  if (busy) { byId('question-inherit').checked = questionSelection(Number(byId('question-day').value)) === null; return; }
  setQuestionSelection(Number(byId('question-day').value), byId('question-inherit').checked ? null : [...questionDraft.default]);
  renderQuestionEditor();
});
byId('cancel-question-source').addEventListener('click', () => {
  if (busy || !state.questionSchedule) return;
  questionsDirty = false;
  byId('question-error').hidden = true;
  renderQuestions();
});
if (!cloud) {
  byId('cloud-toggle').addEventListener('click', () => {
    const enabled = !state.cloudBase.enabled;
    update('cloud', {enabled}, enabled ? 'CloudBase 控制台已开启' : 'CloudBase 控制台已关闭');
  });
  byId('upload-questions').addEventListener('click', () => {
    const name = byId('question-subject-name').value.trim();
    const file = byId('questions-file');
    upload('questions', file, state.questionUploadLimitMb, '题库已导入', name);
  });
  byId('upload-image').addEventListener('click', () => upload('image', byId('wallpaper-file'), 24, '图片已设为电视背景'));
  byId('upload-video').addEventListener('click', () => upload('video', byId('wallpaper-file'), 200, '视频已设为电视背景'));
}

if (token) run(async () => render(await request('state')), '正在连接电视…');
