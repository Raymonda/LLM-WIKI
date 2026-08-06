/**
 * 行级 diff 工具：基于 LCS（最长公共子序列）计算两个文本的差异
 */

export interface DiffLine {
  type: 'added' | 'removed' | 'unchanged'
  text: string
}

export interface DiffResult {
  lines: DiffLine[]
  added: number
  removed: number
  total: number
}

export function computeDiff(oldText: string, newText: string): DiffResult {
  const oldLines = oldText.split('\n')
  const newLines = newText.split('\n')
  const n = oldLines.length
  const m = newLines.length

  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))

  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      dp[i][j] = oldLines[i - 1] === newLines[j - 1]
        ? dp[i - 1][j - 1] + 1
        : Math.max(dp[i - 1][j], dp[i][j - 1])
    }
  }

  const lines: DiffLine[] = []
  let i = n
  let j = m

  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      lines.unshift({ type: 'unchanged', text: oldLines[i - 1] })
      i--
      j--
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      lines.unshift({ type: 'added', text: newLines[j - 1] })
      j--
    } else {
      lines.unshift({ type: 'removed', text: oldLines[i - 1] })
      i--
    }
  }

  let added = 0
  let removed = 0
  for (const l of lines) {
    if (l.type === 'added') added++
    else if (l.type === 'removed') removed++
  }

  return { lines, added, removed, total: added + removed }
}
