/* 角色展示辅助：角色名 → 中文标签 / 语义色 */
export const ROLE_LABEL = {
  READER: '读者',
  AUTHOR: '作者',
  ADMIN: '站长',
}

export const ROLE_ORDER = ['READER', 'AUTHOR', 'ADMIN']

export function roleLabel(role) {
  return ROLE_LABEL[role] || role || '读者'
}

/** 是否至少达到指定角色（ADMIN > AUTHOR > READER） */
export function roleAtLeast(role, required) {
  return ROLE_ORDER.indexOf(role) >= ROLE_ORDER.indexOf(required)
}
