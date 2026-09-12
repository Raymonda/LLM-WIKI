import { describe, expect, it } from 'vitest'
import { isSourceDeprecated, validateDeprecateForm } from '../sourceLifecycle'

describe('isSourceDeprecated', () => {
  it('shouldReturnTrueWhenLifecycleStatusIsDeprecated', () => {
    expect(isSourceDeprecated({ lifecycleStatus: 'DEPRECATED' })).toBe(true)
  })

  it('shouldReturnFalseWhenLifecycleStatusIsActive', () => {
    expect(isSourceDeprecated({ lifecycleStatus: 'ACTIVE' })).toBe(false)
  })

  it('shouldReturnFalseWhenSourceIsNullOrFieldMissing', () => {
    expect(isSourceDeprecated(null)).toBe(false)
    expect(isSourceDeprecated(undefined)).toBe(false)
    expect(isSourceDeprecated({})).toBe(false)
    expect(isSourceDeprecated({ lifecycleStatus: null })).toBe(false)
  })
})

describe('validateDeprecateForm', () => {
  it('shouldPassWhenCategoryIsNotOther', () => {
    expect(validateDeprecateForm('OUTDATED', '')).toBe(true)
    expect(validateDeprecateForm('SUPERSEDED', '')).toBe(true)
    expect(validateDeprecateForm('ERRONEOUS', '')).toBe(true)
  })

  it('shouldRejectWhenCategoryIsOtherWithoutReason', () => {
    expect(validateDeprecateForm('OTHER', '')).toBe(false)
    expect(validateDeprecateForm('OTHER', '   ')).toBe(false)
  })

  it('shouldPassWhenCategoryIsOtherWithReason', () => {
    expect(validateDeprecateForm('OTHER', '包含敏感信息')).toBe(true)
  })
})
