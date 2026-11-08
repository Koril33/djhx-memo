// Local presentation only. Never translate note content or user-provided names.
export type Language = 'zh-CN' | 'en';
export const messages = {
  desktopDevice: { 'zh-CN': '{platform} 桌面', en: '{platform} Desktop' },
  localOnly: {
    'zh-CN': '仅本机',
    en: 'Local only',
  },
  pending: {
    'zh-CN': '待同步 · {count}',
    en: 'Pending · {count}',
  },
  syncing: {
    'zh-CN': '正在同步',
    en: 'Syncing',
  },
  synced: {
    'zh-CN': '已同步',
    en: 'Synced',
  },
  offline: {
    'zh-CN': '离线 · 本地可用',
    en: 'Offline · available locally',
  },
  authRequired: {
    'zh-CN': '需重新登录',
    en: 'Sign in again',
  },
  recoveryRequired: {
    'zh-CN': '需恢复处理',
    en: 'Recovery required',
  },
  syncFailed: {
    'zh-CN': '同步失败',
    en: 'Sync failed',
  },
  opening: {
    'zh-CN': '正在打开',
    en: 'Opening',
  },
  untitled: {
    'zh-CN': '无标题',
    en: 'Untitled',
  },
  noRecord: {
    'zh-CN': '尚无记录',
    en: 'No record yet',
  },
  excerptEmpty: {
    'zh-CN': '静静记录，随时回来。',
    en: 'Your notes are here whenever you return.',
  },
  profileChanged: {
    'zh-CN': '当前核心空间已变化。此处输入仍保留，请先导出草稿，再进入当前空间。',
    en: 'The active space has changed. Your input is kept here. Export this draft before entering the current space.',
  },
  conflictOpened: {
    'zh-CN': '当前修改已保留为冲突副本，已为你打开。',
    en: 'Your changes were kept as a conflict copy, now open.',
  },
  recoveryReadOnlyError: {
    'zh-CN': '恢复空间只读，请在账号设置中复制需要保留的笔记。',
    en: 'Recovery spaces are read-only. Copy the notes you need from account settings.',
  },
  profileSwitched: {
    'zh-CN': '已切换空间；各空间的数据相互独立。',
    en: 'Space switched. Each space keeps its own data.',
  },
  registered: {
    'zh-CN': '注册成功，已进入账号空间。',
    en: 'Account created. Your account space is open.',
  },
  signedIn: {
    'zh-CN': '登录成功，已进入账号空间。',
    en: 'Signed in. Your account space is open.',
  },
  imported: {
    'zh-CN': '已复制 {count} 条笔记；来源空间保留原文。',
    en: 'Copied {count} notes. Originals remain in the source space.',
  },
  loggedOut: {
    'zh-CN': '已注销，账号本地内容仍保留。',
    en: 'Signed out. Local account notes are kept.',
  },
  loggedOutOffline: {
    'zh-CN': '已在本机注销。远程吊销尚未完成，请从另一台已登录设备吊销本设备。',
    en: 'Signed out locally. Remote revocation is pending. Revoke this device from another signed-in device.',
  },
  movedToTrash: {
    'zh-CN': '已移到回收站，正文将保留。',
    en: 'Moved to trash. Content is kept.',
  },
  restored: {
    'zh-CN': '已恢复笔记。',
    en: 'Note restored.',
  },
  previewChanged: {
    'zh-CN': '预览内容已更新，请再次点击当前任务。',
    en: 'The preview changed. Click the current task again.',
  },
  exported: {
    'zh-CN': '已导出 ZIP：{path}',
    en: 'ZIP exported: {path}',
  },
  rescued: {
    'zh-CN': '当前文字已保全为 Markdown：{path}',
    en: 'Current text saved as Markdown: {path}',
  },
  copied: {
    'zh-CN': '已复制当前编辑文字。',
    en: 'Current text copied.',
  },
  revokedNotice: {
    'zh-CN': '已吊销设备。设备本地内容保留，下一次联网时停止同步。',
    en: 'Device revoked. Its local content is kept; syncing stops on its next connection.',
  },
  newNote: {
    'zh-CN': '新建笔记',
    en: 'New note',
  },
  workspace: {
    'zh-CN': '工作空间',
    en: 'Workspace',
  },
  allNotes: {
    'zh-CN': '全部笔记',
    en: 'All notes',
  },
  trash: {
    'zh-CN': '回收站',
    en: 'Trash',
  },
  exportTrash: {
    'zh-CN': '导出回收站',
    en: 'Export trash',
  },
  exportNotes: {
    'zh-CN': '导出笔记',
    en: 'Export notes',
  },
  settings: {
    'zh-CN': '账号与设置',
    en: 'Account & settings',
  },
  localSpace: {
    'zh-CN': '本机空间',
    en: 'Local space',
  },
  recoverySpace: {
    'zh-CN': '恢复保全空间',
    en: 'Recovery space',
  },
  accountSpace: {
    'zh-CN': '账号空间',
    en: 'Account space',
  },
  openingEllipsis: {
    'zh-CN': '正在打开…',
    en: 'Opening…',
  },
  localTagline: {
    'zh-CN': '文字先留在你身边',
    en: 'Your words stay with you',
  },
  switchSpace: {
    'zh-CN': '切换空间',
    en: 'Switch space',
  },
  noteList: {
    'zh-CN': '笔记列表',
    en: 'Note list',
  },
  records: {
    'zh-CN': '{count} 条记录',
    en: '{count} notes',
  },
  matches: {
    'zh-CN': '{count} 条匹配',
    en: '{count} matches',
  },
  refreshList: {
    'zh-CN': '刷新列表',
    en: 'Refresh note list',
  },
  refresh: {
    'zh-CN': '刷新',
    en: 'Refresh',
  },
  searchCurrent: {
    'zh-CN': '搜索当前空间笔记',
    en: 'Search notes in the current space',
  },
  searchPlaceholder: {
    'zh-CN': '搜索文字…',
    en: 'Search notes…',
  },
  localBadge: {
    'zh-CN': '本地',
    en: 'Local',
  },
  trashContext: {
    'zh-CN': '内容一直保留，随时可以恢复',
    en: 'Content is kept and can be restored anytime',
  },
  searchContext: {
    'zh-CN': '字面匹配 · 支持中文短词',
    en: 'Literal matching · short Chinese queries supported',
  },
  recentNotes: {
    'zh-CN': '最近记录',
    en: 'Recent notes',
  },
  conflictCopy: {
    'zh-CN': '冲突副本',
    en: 'Conflict copy',
  },
  pendingShort: {
    'zh-CN': '待同步',
    en: 'Pending',
  },
  noSearchResults: {
    'zh-CN': '没有找到笔记',
    en: 'No notes found',
  },
  emptyTrash: {
    'zh-CN': '回收站是空的',
    en: 'Trash is empty',
  },
  emptyNotes: {
    'zh-CN': '这里，等待第一个念头',
    en: 'A place for your first thought',
  },
  searchHelp: {
    'zh-CN': '试试更短的文字，或清空搜索。',
    en: 'Try a shorter query or clear the search.',
  },
  trashHelp: {
    'zh-CN': '删除的笔记会留在这里。',
    en: 'Deleted notes stay here.',
  },
  emptyHelp: {
    'zh-CN': '点击新建，或者直接在右侧开始写。',
    en: 'Create a note or start writing on the right.',
  },
  noteEditor: {
    'zh-CN': '笔记编辑器',
    en: 'Note editor',
  },
  localNotes: {
    'zh-CN': '本机笔记',
    en: 'Local notes',
  },
  newDraft: {
    'zh-CN': '新草稿',
    en: 'New draft',
  },
  sync: {
    'zh-CN': '同步',
    en: 'Sync',
  },
  trayUnavailable: {
    'zh-CN': '系统托盘不可用，关闭窗口会先保存并退出；你可以从应用菜单重新打开。',
    en: 'The system tray is unavailable. Closing saves and exits; reopen from your application menu.',
  },
  keyringUnavailable: {
    'zh-CN': '系统密钥环不可用。凭据仅保留在本次进程，重启后需重新登录；笔记保存在本机。',
    en: 'The system keyring is unavailable. Credentials last for this session only; sign in again after restarting. Notes are stored locally.',
  },
  signInAgainHelp: {
    'zh-CN': '此设备需重新登录，本地编辑和导出继续可用。',
    en: 'Sign in again on this device. Local editing and export remain available.',
  },
  signInAgain: {
    'zh-CN': '重新登录',
    en: 'Sign in again',
  },
  recoveryHelp: {
    'zh-CN': '服务器恢复代次变化，旧数据已保全。请在设置中查看恢复空间并选择复制。',
    en: 'The server recovery epoch changed. Previous data is preserved. Review recovery spaces in settings and choose what to copy.',
  },
  view: {
    'zh-CN': '查看',
    en: 'View',
  },
  readOnlyHelp: {
    'zh-CN': '恢复空间只读。先导出保全，或切回账号空间后明确选择复制。',
    en: 'This recovery space is read-only. Export a copy, or switch to the account space and choose to copy.',
  },
  dismiss: {
    'zh-CN': '收起消息',
    en: 'Dismiss message',
  },
  inputPreserved: {
    'zh-CN': '当前输入仍保留在内存中',
    en: 'Your input is still kept in memory',
  },
  generationHelp: {
    'zh-CN': '本机版本已变化，另存副本可保留双方内容。',
    en: 'The local version changed. Save a copy to keep both versions.',
  },
  saveHelp: {
    'zh-CN': '请重试保存，或先复制、导出此处文字。',
    en: 'Retry saving, or copy or export your current text first.',
  },
  saveAsCopy: {
    'zh-CN': '另存为新笔记',
    en: 'Save as a new note',
  },
  retrySave: {
    'zh-CN': '重试保存',
    en: 'Retry saving',
  },
  copyText: {
    'zh-CN': '复制文字',
    en: 'Copy text',
  },
  rescueSwitch: {
    'zh-CN': '导出草稿并进入当前空间',
    en: 'Export draft and enter current space',
  },
  exportCurrent: {
    'zh-CN': '导出当前文字',
    en: 'Export current text',
  },
  staleDelete: {
    'zh-CN': '删除未执行，内容已保留为冲突副本。',
    en: 'Deletion was not applied. Content was kept as a conflict copy.',
  },
  conflictHelp: {
    'zh-CN': '修改与另一版本相遇，两份内容都已保留。',
    en: 'These changes met another version. Both versions are kept.',
  },
  conflictSource: {
    'zh-CN': '来源：{name} · {time}',
    en: 'From: {name} · {time}',
  },
  originalNote: {
    'zh-CN': '原笔记：{id}',
    en: 'Original note: {id}',
  },
  deletedHelp: {
    'zh-CN': '此笔记在回收站中。恢复后可以继续编辑。',
    en: 'This note is in trash. Restore it to keep editing.',
  },
  restoreNote: {
    'zh-CN': '恢复笔记',
    en: 'Restore note',
  },
  editorMode: {
    'zh-CN': '编辑模式',
    en: 'Editor mode',
  },
  source: {
    'zh-CN': '源码',
    en: 'Source',
  },
  preview: {
    'zh-CN': '预览',
    en: 'Preview',
  },
  split: {
    'zh-CN': '对照',
    en: 'Split',
  },
  moveToTrash: {
    'zh-CN': '移到回收站',
    en: 'Move to trash',
  },
  openingLocal: {
    'zh-CN': '正在打开本机空间…',
    en: 'Opening local space…',
  },
  openFailed: {
    'zh-CN': '无法打开本机空间。',
    en: 'Could not open local space.',
  },
  retry: {
    'zh-CN': '重试',
    en: 'Retry',
  },
  readingPreview: {
    'zh-CN': '阅读预览',
    en: 'Reading preview',
  },
  safePreview: {
    'zh-CN': '安全 Markdown 预览',
    en: 'Safe Markdown preview',
  },
  previewEmptyTitle: {
    'zh-CN': '为想法留一处安静的地方',
    en: 'A quiet place for your thoughts',
  },
  previewEmptyHelp: {
    'zh-CN': '切到源码，写下第一行。它会自动保存到这台设备。',
    en: 'Switch to source and write your first line. It saves automatically on this device.',
  },
  characters: {
    'zh-CN': '{count} 字符',
    en: '{count} characters',
  },
  saveFailed: {
    'zh-CN': '保存失败',
    en: 'Save failed',
  },
  saving: {
    'zh-CN': '保存中',
    en: 'Saving',
  },
  saved: {
    'zh-CN': '已保存到本机',
    en: 'Saved locally',
  },
  blankDraft: {
    'zh-CN': '空白草稿',
    en: 'Blank draft',
  },
  settingsTagline: {
    'zh-CN': '你的文字，你的空间',
    en: 'Your words, your space',
  },
  closeSettings: {
    'zh-CN': '关闭设置',
    en: 'Close settings',
  },
  accountTab: {
    'zh-CN': '空间与账号',
    en: 'Spaces & accounts',
  },
  devicesTab: {
    'zh-CN': '登录设备',
    en: 'Devices',
  },
  dataTab: {
    'zh-CN': '数据与导出',
    en: 'Data & export',
  },
  appearanceTab: {
    'zh-CN': '偏好设置',
    en: 'Preferences',
  },
  currentSpace: {
    'zh-CN': '当前空间',
    en: 'Current space',
  },
  localAvatar: {
    'zh-CN': '本',
    en: 'L',
  },
  cloudAvatar: {
    'zh-CN': '云',
    en: 'C',
  },
  localPrivate: {
    'zh-CN': '独立本机空间 · 不自动上传',
    en: 'Separate local space · no automatic upload',
  },
  logout: {
    'zh-CN': '注销',
    en: 'Sign out',
  },
  readOnly: {
    'zh-CN': '只读保全',
    en: 'Read-only archive',
  },
  loginOther: {
    'zh-CN': '登录其他账号',
    en: 'Sign in to another account',
  },
  registerInvite: {
    'zh-CN': '使用邀请码注册',
    en: 'Register with an invitation',
  },
  registerLink: {
    'zh-CN': '注册账号 →',
    en: 'Create account →',
  },
  loginLink: {
    'zh-CN': '已有账号，登录 →',
    en: 'Have an account? Sign in →',
  },
  serverAddress: {
    'zh-CN': '服务器地址',
    en: 'Server address',
  },
  debugServerHelp: {
    'zh-CN': '调试构建允许显式 HTTP；生产请使用 HTTPS。',
    en: 'Debug builds allow explicit HTTP. Use HTTPS in production.',
  },
  serverHelp: {
    'zh-CN': '使用你的 HTTPS 服务端地址。',
    en: 'Use your HTTPS server address.',
  },
  username: {
    'zh-CN': '用户名',
    en: 'Username',
  },
  deviceName: {
    'zh-CN': '设备名称',
    en: 'Device name',
  },
  devicePlaceholder: {
    'zh-CN': '例如：家里的电脑',
    en: 'For example: Home computer',
  },
  password: {
    'zh-CN': '密码',
    en: 'Password',
  },
  passwordPlaceholder: {
    'zh-CN': '12～128 个字符',
    en: '12–128 characters',
  },
  invite: {
    'zh-CN': '邀请码',
    en: 'Invitation code',
  },
  invitePlaceholder: {
    'zh-CN': '由服务器管理员提供',
    en: 'Provided by the server administrator',
  },
  accountHelp: {
    'zh-CN': '登录后进入独立账号空间。本机笔记保留，只有你明确选择导入时才复制到账号。',
    en: 'Signing in opens a separate account space. Local notes stay here and are copied only when you choose to import.',
  },
  processing: {
    'zh-CN': '正在处理…',
    en: 'Working…',
  },
  loginSubmit: {
    'zh-CN': '登录并进入账号空间',
    en: 'Sign in and open account space',
  },
  registerSubmit: {
    'zh-CN': '注册并进入账号空间',
    en: 'Register and open account space',
  },
  devicesHelp: {
    'zh-CN': '吊销会停止设备后续联网同步，设备上的本地内容仍保留。',
    en: 'Revoking a device stops future syncing. Its local content is kept.',
  },
  devicesNeedAccount: {
    'zh-CN': '进入账号空间后，可以查看和管理设备。',
    en: 'Open an account space to view and manage devices.',
  },
  currentDevice: {
    'zh-CN': '当前设备',
    en: 'This device',
  },
  revokedAt: {
    'zh-CN': '已吊销 · {time}',
    en: 'Revoked · {time}',
  },
  lastActive: {
    'zh-CN': '最近活动 · {time}',
    en: 'Last active · {time}',
  },
  expiresAt: {
    'zh-CN': '有效期至 {time}',
    en: 'Expires {time}',
  },
  revoked: {
    'zh-CN': '已吊销',
    en: 'Revoked',
  },
  revoke: {
    'zh-CN': '吊销',
    en: 'Revoke',
  },
  noDevices: {
    'zh-CN': '暂无设备记录。',
    en: 'No device records.',
  },
  reload: {
    'zh-CN': '重新读取',
    en: 'Reload',
  },
  exportSpace: {
    'zh-CN': '导出当前空间',
    en: 'Export current space',
  },
  exportHelp: {
    'zh-CN': '先保存当前输入，再将 Markdown 与清单导出为 ZIP。已有文件不会被覆盖。',
    en: 'Save current input first, then export Markdown and its manifest as a ZIP. Existing files are never overwritten.',
  },
  exportDeleted: {
    'zh-CN': '导出回收站内容',
    en: 'Export trash contents',
  },
  exportActive: {
    'zh-CN': '导出全部未删除笔记',
    en: 'Export all active notes',
  },
  copySpace: {
    'zh-CN': '复制本机或恢复空间',
    en: 'Copy local or recovery notes',
  },
  copySpaceHelp: {
    'zh-CN':
      '复制到当前账号时生成新笔记 ID，来源始终保留。相同保存代次重复操作不会产生重复副本；后续编辑的来源不会自动联动。',
    en: 'Copying to this account creates new note IDs and keeps the originals. Repeating the same saved generation creates no duplicates. Later edits to the source are independent.',
  },
  sourceSpace: {
    'zh-CN': '来源空间',
    en: 'Source space',
  },
  targetSpace: {
    'zh-CN': '目标：{name} · {server}',
    en: 'Target: {name} · {server}',
  },
  copyToAccount: {
    'zh-CN': '明确复制到此账号',
    en: 'Copy to this account',
  },
  noteCountSuffix: {
    'zh-CN': '（{count} 条）',
    en: ' ({count} notes)',
  },
  copyNeedAccount: {
    'zh-CN': '先进入目标账号空间，再选择复制来源。',
    en: 'Open the destination account space, then choose a source.',
  },
  syncDetails: {
    'zh-CN': '本地与云端状态',
    en: 'Local & cloud status',
  },
  currentStatus: {
    'zh-CN': '当前状态',
    en: 'Current status',
  },
  lastSync: {
    'zh-CN': '最近成功同步',
    en: 'Last successful sync',
  },
  closeBehavior: {
    'zh-CN': '窗口关闭行为',
    en: 'On window close',
  },
  hideAfterSave: {
    'zh-CN': '保存成功后进入托盘',
    en: 'Save, then hide to tray',
  },
  quitAfterSave: {
    'zh-CN': '保存成功后退出',
    en: 'Save, then exit',
  },
  clientVersion: {
    'zh-CN': '客户端版本',
    en: 'Client version',
  },
  importTitle: {
    'zh-CN': '带上本机的记录？',
    en: 'Bring your local notes?',
  },
  importCount: {
    'zh-CN': '有 {count} 条本机笔记可以复制到',
    en: 'Copy {count} local notes to',
  },
  importHelp: {
    'zh-CN': '原笔记留在本机。复制后的内容会同步到 {server}，之后两份内容各自独立。',
    en: 'Original notes stay on this device. Copies sync to {server}; the two versions remain independent.',
  },
  importLater: {
    'zh-CN': '暂不导入',
    en: 'Not now',
  },
  importSubmit: {
    'zh-CN': '导入 {count} 条本机笔记',
    en: 'Import {count} local notes',
  },
  language: {
    'zh-CN': '语言',
    en: 'Language',
  },
  followSystem: {
    'zh-CN': '跟随系统',
    en: 'Follow system',
  },
  appearance: {
    'zh-CN': '外观',
    en: 'Appearance',
  },
  light: {
    'zh-CN': '浅色',
    en: 'Light',
  },
  dark: {
    'zh-CN': '深色',
    en: 'Dark',
  },
  timeZone: {
    'zh-CN': '显示时区',
    en: 'Display time zone',
  },
  shanghai: {
    'zh-CN': '上海 · UTC+08:00',
    en: 'Shanghai · UTC+08:00',
  },
  utc: {
    'zh-CN': 'UTC · UTC+00:00',
    en: 'UTC · UTC+00:00',
  },
  preferencesHelp: {
    'zh-CN': '偏好仅保存在此设备，不更改笔记、账号数据或系统设置。',
    en: 'Preferences stay on this device and do not change notes, account data, or system settings.',
  },
  preferencesFailed: {
    'zh-CN': '无法保存本机偏好，请检查应用存储权限。',
    en: 'Could not save preferences. Check application storage permissions.',
  },
  editorPlaceholder: {
    'zh-CN': '从一个念头开始…\n\n支持 Markdown，输入后自动保存到本机。',
    en: 'Start with a thought…\n\nMarkdown supported. Changes save automatically on this device.',
  },
  imageLabel: {
    'zh-CN': '图片',
    en: 'Image',
  },
  imageBlocked: {
    'zh-CN': '已禁止自动加载',
    en: 'Automatic loading blocked',
  },
  taskUncheck: {
    'zh-CN': '取消完成',
    en: 'Mark incomplete',
  },
  taskCheck: {
    'zh-CN': '标记完成',
    en: 'Mark complete',
  },
  invalidForm: {
    'zh-CN': '请检查服务器地址、用户名、密码、邀请码和设备名称。',
    en: 'Check the server address, username, password, invitation, and device name.',
  },
  invalidServer: {
    'zh-CN': '请输入不含账号信息的 HTTPS 服务器地址。',
    en: 'Enter an HTTPS server address without embedded credentials.',
  },
  invalidUsername: {
    'zh-CN': '用户名需为 3～32 个小写英文字母、数字、下划线或连字符，并以字母或数字开头。',
    en: 'Use 3–32 lowercase letters, digits, underscores, or hyphens, starting with a letter or digit.',
  },
  invalidPassword: {
    'zh-CN': '密码需为 12～128 个字符。',
    en: 'Use a password of 12–128 characters.',
  },
  invalidDevice: {
    'zh-CN': '设备名称不能为空，且不能超过 128 个字符。',
    en: 'Device name must contain 1–128 characters.',
  },
  missingInvite: {
    'zh-CN': '请输入邀请码。',
    en: 'Enter an invitation code.',
  },
  genericError: {
    'zh-CN': '操作未完成，请重试。',
    en: 'The operation could not be completed. Please try again.',
  },
  errorAuth: {
    'zh-CN': '用户名或密码不正确。',
    en: 'Incorrect username or password.',
  },
  errorAuthRequired: {
    'zh-CN': '登录已过期或设备已被吊销，请重新登录。本地笔记已保留。',
    en: 'Your session expired or this device was revoked. Sign in again. Local notes are kept.',
  },
  errorInvite: {
    'zh-CN': '邀请码无效、已使用或已过期。',
    en: 'The invitation is invalid, already used, or expired.',
  },
  errorUsername: {
    'zh-CN': '用户名已被使用。',
    en: 'This username is already taken.',
  },
  errorRate: {
    'zh-CN': '请求过于频繁，请稍后重试。',
    en: 'Too many requests. Try again later.',
  },
  errorDevice: {
    'zh-CN': '设备标识不可用，请重启应用后重试。',
    en: 'Device identity is unavailable. Restart the app and try again.',
  },
  errorRecovery: {
    'zh-CN': '服务器恢复代次已变化，本地内容已保全。请检查恢复空间。',
    en: 'The server recovery epoch changed. Local data is preserved. Review recovery spaces.',
  },
  errorCursor: {
    'zh-CN': '同步位置无效，本地内容已保留。',
    en: 'The sync position is invalid. Local content is kept.',
  },
  errorProtocol: {
    'zh-CN': '服务器协议不兼容，请升级客户端。',
    en: 'The server protocol is incompatible. Update the client.',
  },
  errorInvalid: {
    'zh-CN': '请求参数无效，请检查后重试。',
    en: 'Invalid request. Check your input and try again.',
  },
  errorStorage: {
    'zh-CN': '本机保存失败，请检查磁盘空间与权限；当前输入仍保留。',
    en: 'Local storage failed. Check disk space and permissions. Current input is kept.',
  },
  errorSchema: {
    'zh-CN': '本机数据版本较新，请升级客户端后再打开。',
    en: 'Local data uses a newer schema. Update the client before opening it.',
  },
  errorMissing: {
    'zh-CN': '笔记或空间不存在。',
    en: 'The note or space was not found.',
  },
  errorNetwork: {
    'zh-CN': '无法连接服务器。本机内容保留，联网后可重试。',
    en: 'Could not reach the server. Local content is kept; retry when connected.',
  },
  errorBusy: {
    'zh-CN': '服务器暂时不可用，请稍后重试。',
    en: 'The server is temporarily unavailable. Try again later.',
  },
  errorLarge: {
    'zh-CN': '笔记超过大小限制，请缩短内容后重试。',
    en: 'This note exceeds the size limit. Shorten it and try again.',
  },
  errorGeneration: {
    'zh-CN': '本机版本已变化，请另存副本以保留当前输入。',
    en: 'The local version changed. Save a copy to keep your input.',
  },
  errorSession: {
    'zh-CN': '当前账号或空间已变化，请重试。',
    en: 'The active account or space changed. Try again.',
  },
  errorExport: {
    'zh-CN': '导出未完成，请检查磁盘空间与所选目录权限。',
    en: 'Export did not finish. Check disk space and directory permissions.',
  },
  errorFileExists: {
    'zh-CN': '目标文件已存在，请使用新的文件名；已有文件未改动。',
    en: 'The destination exists. Choose a new file name. The existing file was not changed.',
  },
  errorPermission: {
    'zh-CN': '没有权限写入所选目录，请重新选择。',
    en: 'The selected directory is not writable. Choose another directory.',
  },
  errorLocalPath: {
    'zh-CN': '请选择有效的本地导出目录。',
    en: 'Choose a valid local export directory.',
  },
  errorBrowser: {
    'zh-CN': '无法打开系统浏览器。',
    en: 'Could not open the system browser.',
  },
  errorLink: {
    'zh-CN': '仅允许打开 HTTP 或 HTTPS 链接。',
    en: 'Only HTTP and HTTPS links can be opened.',
  },
  errorWindow: {
    'zh-CN': '窗口操作未完成，请重试。',
    en: 'The window operation did not finish. Try again.',
  },
  errorDeleted: {
    'zh-CN': '回收站笔记需先恢复才能编辑。',
    en: 'Restore this note from trash before editing.',
  },
} as const;
export type MessageKey = keyof typeof messages;
export type MessageArgs = Record<string, string | number>;
export interface Message {
  key: MessageKey;
  args?: MessageArgs;
}
export type Translator = (key: MessageKey, args?: MessageArgs) => string;
export type CountMessageKey = {
  [Key in MessageKey]: (typeof messages)[Key]['en'] extends `${string}{count}${string}`
    ? Key
    : never;
}[MessageKey];
// Every count-bearing English message must explicitly define its singular form.
// Keep the plural catalogue as the default, including zero and nonnumeric display values.
export const englishSingularMessages = {
  pending: 'Pending · {count}',
  imported: 'Copied {count} note. The original remains in the source space.',
  records: '{count} note',
  matches: '{count} match',
  characters: '{count} character',
  noteCountSuffix: ' ({count} note)',
  importCount: 'Copy {count} local note to',
  importSubmit: 'Import {count} local note',
} as const satisfies Record<CountMessageKey, string>;
const englishPluralRules = new Intl.PluralRules('en');
export function createTranslator(language: Language): Translator {
  return (key, args = {}) => {
    const template =
      language === 'en' &&
      Object.hasOwn(englishSingularMessages, key) &&
      args.count !== undefined &&
      englishPluralRules.select(Number(args.count)) === 'one'
        ? englishSingularMessages[key as CountMessageKey]
        : messages[key][language];
    return template.replace(/\{(\w+)\}/g, (match, name: string) => String(args[name] ?? match));
  };
}
export class LocalizedError extends Error {
  constructor(public readonly key: MessageKey) {
    super(key);
  }
}
