/**
 * 将 Wiki 页面 summary 中可能存在的 Markdown 源码符号清洗为紧凑纯文本，
 * 用于卡片/列表等单行或多行预览场景。
 *
 * 适用场景：STRUCTURED 文档章节参考页的 summary 由 LLM 输出结构化 Markdown
 * （如 `## 核心要点 - ...`），直接插值会暴露原始符号。
 */
export function formatSummaryPreview(raw: string | null | undefined, maxLength = 160): string {
  if (!raw) return ''
  let text = String(raw)

  // 去除代码块围栏
  text = text.replace(/```[\s\S]*?```/g, ' ')
  text = text.replace(/`([^`]+)`/g, '$1')

  // 去除图片与保留链接文字
  text = text.replace(/!\[[^\]]*]\([^)]*\)/g, '')
  text = text.replace(/\[([^\]]+)]\([^)]*\)/g, '$1')

  // 去除 wiki 链接 [[xxx]]
  text = text.replace(/\[\[([^\]]+)]]/g, '$1')

  // 去除标题前缀 # ## ### 等（行首）
  text = text.replace(/^\s{0,3}#{1,6}\s+/gm, '')

  // 去除引用块标记
  text = text.replace(/^\s{0,3}>\s?/gm, '')

  // 去除列表标记 - * + 1.
  text = text.replace(/^\s{0,3}[-*+]\s+/gm, '')
  text = text.replace(/^\s{0,3}\d+\.\s+/gm, '')

  // 去除表格分隔符行
  text = text.replace(/^\s*\|?\s*[:\-|\s]+\|\s*$/gm, '')
  text = text.replace(/\|/g, ' ')

  // 去除强调符号 ** __ * _ ~~
  text = text.replace(/\*\*([^*]+)\*\*/g, '$1')
  text = text.replace(/__([^_]+)__/g, '$1')
  text = text.replace(/(^|\W)\*([^*\n]+)\*/g, '$1$2')
  text = text.replace(/(^|\W)_([^_\n]+)_/g, '$1$2')
  text = text.replace(/~~([^~]+)~~/g, '$1')

  // 去除水平线
  text = text.replace(/^\s*[-*_]{3,}\s*$/gm, '')

  // 折叠空白：所有换行/制表符 → 单个空格
  text = text.replace(/[\r\n\t]+/g, ' ').replace(/\s{2,}/g, ' ').trim()

  if (maxLength > 0 && text.length > maxLength) {
    text = text.slice(0, maxLength).trimEnd() + '…'
  }
  return text
}
