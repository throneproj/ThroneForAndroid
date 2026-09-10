## ADDED Requirements

### Requirement: AMOLED 纯黑模式开关

应用 MUST 提供 AMOLED 纯黑模式开关并持久化该设置。开关启用且系统处于夜间模式时，应用主题与 Dialog 主题 MUST 在既有主题之上叠加纯黑 overlay style（背景与表面色为纯黑、立体卡片表面使用近黑提升色）；开关关闭或处于非夜间模式时 MUST NOT 叠加该 overlay。该设置 MUST 纳入应用备份与恢复范围。开关文案 MUST 使用可翻译资源并同步适用语言。本开关 MUST NOT 改变默认主题选择、动态主题取色行为或既有主题 ID 语义。

#### Scenario: 夜间模式启用 AMOLED 纯黑

- **GIVEN** 用户启用 AMOLED 纯黑开关且应用处于夜间模式
- **WHEN** 用户打开任意主题 Activity 或 Dialog
- **THEN** 界面背景与表面色为纯黑
- **AND** 立体卡片使用近黑提升表面色
- **AND** 原有主题色强调元素保持不变

#### Scenario: 开关关闭或非夜间模式

- **GIVEN** AMOLED 纯黑开关关闭，或开关启用但应用处于非夜间模式
- **WHEN** 用户打开任意页面
- **THEN** 主题外观与未引入该开关时一致

#### Scenario: 设置纳入备份

- **GIVEN** 用户已启用 AMOLED 纯黑开关
- **WHEN** 用户执行备份并在其他设备恢复
- **THEN** 该开关状态随备份恢复