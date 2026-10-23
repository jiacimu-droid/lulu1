import { isImpact, touchFamily, touchForce } from './touch-response.mjs'

const PALM_IMPACT = Object.freeze({
  hair: [7, -13, 8, 3], forehead: [10, -11, 8, 3],
  'beast-ear': [14, -5, 10, 3], ear: [21, -6, 14, 4],
  eye: [12, -6, 8, 3], nose: [4, -12, 3, 4],
  cheek: [25, -4, 16, 3], mouth: [18, -8, 10, 4],
  neck: [7, -12, 6, 6], throat: [4, -13, 4, 7],
  collarbone: [5, -8, 4, 8], chest: [5, -5, 4, 10],
  arm: [7, -3, 5, 5], hand: [5, -2, 3, 3], body: [5, -5, 4, 9],
})

/** All axes are optional actuators, validated against the imported rig.
 * Region × tool × force × elapsed time; ordinal only varies tiny mannerisms.
 * It never rotates emotions or bypasses the gradual visual response state.
 */
export function touchReflexFor(region, gesture, ordinal = 0, state = {}, elapsedMs = 0) {
  const family = touchFamily(region)
  const side = region.endsWith('-left') ? -1 : region.endsWith('-right') ? 1 : 0
  const direction = gesture === 'stroke-left' || gesture === 'slap-left' ? -1
    : gesture === 'stroke-right' || gesture === 'slap-right' ? 1 : 0
  const variation = Math.abs(ordinal) % 3
  const force = touchForce(state.force)
  const scale = .55 + force * .85
  const shy = Math.min(1, Math.max(0, (state.shyness || 0) / 100))
  const response = {
    yaw: 0, pitch: 0, roll: 0,
    body: { yaw: 0, pitch: 0, roll: 0 }, face: {}, parameters: {},
  }
  const closeTouchedEye = (value) => {
    response.face[side < 0 ? 'eyeLeftOpen' : 'eyeRightOpen'] = value
  }
  if (isImpact(gesture)) {
    // First recoil in the actual direction, THEN knitted brows/pout/tears.
    const recoil = elapsedMs < 180 ? 1 : Math.max(.12, 1 - (elapsedMs - 180) / 650)
    const swipe = direction || -side || 1
    const [yaw, pitch, roll, torso] = PALM_IMPACT[family] || PALM_IMPACT.body
    response.yaw = swipe * yaw * scale * recoil
    response.roll = swipe * roll * scale * recoil
    response.pitch = pitch * scale * recoil
    response.body = {
      yaw: swipe * torso * .65 * scale * recoil,
      pitch: -torso * scale * recoil,
      roll: swipe * torso * .6 * scale * recoil,
    }
    response.face = elapsedMs < 180
      ? { eyeOpen: .12, browY: .35, smile: -.15 }
      : { eyeOpen: .72 - force * .2, browY: -.23, browAngle: -.45,
          browForm: -.4, smile: -.32, pout: .2 + force * .22,
          gazeX: -swipe * .2, gazeY: -.14 }
    if (family === 'eye') closeTouchedEye(elapsedMs < 180 ? .02 : .3)
    if (family === 'beast-ear') {
      response.parameters[side < 0 ? 'se_wuli_x_l' : 'se_wuli_x_r'] = -.7 * scale * recoil
    }
    return response
  }

  const kiss = gesture === 'kiss'
  const stroking = gesture.startsWith('stroke') || gesture === 'hold'
  const soft = stroking || kiss
  const blink = variation === 1 ? .1 : 0
  switch (family) {
    case 'hair': case 'forehead':
      response.pitch = kiss ? -3 : soft ? 10 + variation * 2 : 4
      response.yaw = direction * 7
      response.roll = (direction || (variation === 1 ? -1 : 1)) * (kiss ? 3 : 4)
      response.face = { eyeOpen: kiss ? .24 : soft ? .48 + blink : .85,
        eyeSmile: kiss ? .25 : .32, smile: kiss ? .25 : .22 }
      break
    case 'beast-ear':
      response.yaw = -side * (kiss ? 9 : 5)
      response.pitch = kiss ? -4 : 5
      response.roll = side * (kiss ? 9 : 6)
      response.face = { eyeOpen: kiss ? .48 : .68, smile: .17 + shy * .15 }
      response.parameters[side < 0 ? 'se_wuli_x_l' : 'se_wuli_x_r'] =
        (kiss ? -.52 : .28 + .16 * Math.sin(elapsedMs / 120)) * scale
      break
    case 'ear':
      response.yaw = -side * (kiss ? 12 : 7)
      response.roll = side * (kiss ? 11 : 7)
      response.pitch = kiss ? -4 : 2
      response.body.roll = -side * 2
      response.face = { eyeOpen: kiss ? .46 : .7, browY: .12,
        smile: kiss ? .13 : .2, gazeX: side * .25 }
      break
    case 'eye':
      response.yaw = -side * (kiss ? 3 : 6)
      response.pitch = kiss ? -1 : -3
      response.face = { eyeOpen: .9, eyeSmile: kiss ? .2 : 0, smile: kiss ? .2 : .05 }
      closeTouchedEye(kiss ? .03 : .12)
      break
    case 'nose':
      response.pitch = kiss ? 5 : -5
      response.face = { eyeOpen: kiss ? .55 : .65, smile: kiss ? .3 : .16,
        browY: kiss ? .1 : .18 }
      break
    case 'cheek':
      response.yaw = side * (kiss ? 5 : 9 + variation)
      response.roll = -side * (kiss ? 8 : 5)
      response.pitch = kiss ? -2 - shy * 6 : 6
      response.face = { eyeOpen: kiss ? .8 - shy * .35 : .58,
        eyeSmile: kiss ? .2 : .32, smile: kiss ? .2 + shy * .2 : .28,
        gazeX: kiss ? -side * .3 : side * .15,
        cheekPuff: kiss ? .08 : .04 }
      break
    case 'mouth':
      response.pitch = kiss ? -3 - shy * 6 : -4
      response.roll = kiss ? (variation === 1 ? -1 : 1) * 3 : 0
      response.face = kiss
        ? { eyeOpen: elapsedMs < 160 ? .95 : .22, eyeSmile: .12,
            smile: .08, pout: .3, gazeY: -.2 }
        : { eyeOpen: .88, browY: .08, smile: .02, pout: .17, gazeY: -.25 }
      break
    case 'neck':
      response.yaw = kiss ? 9 : direction * 7 || (variation === 1 ? -6 : 6)
      response.roll = kiss ? 8 : 5
      response.pitch = kiss ? -4 : 1
      response.body.roll = -response.roll * .35
      response.face = { eyeOpen: kiss ? .44 : .76, smile: kiss ? .13 : .16, gazeX: .2 }
      break
    case 'throat':
      response.pitch = kiss ? (elapsedMs < 230 ? 10 : -6) : (elapsedMs < 180 ? 5 : -2)
      response.yaw = kiss ? 6 : 0
      response.face = { eyeOpen: kiss ? .38 : .8, browY: .12, smile: .03, gazeY: -.2 }
      break
    case 'collarbone':
      response.pitch = kiss ? -11 : -6
      response.body.pitch = kiss ? -5 : -2
      response.body.roll = kiss ? 3 : 0
      response.face = { eyeOpen: kiss ? .46 : .78, browY: .1, gazeY: -.35, smile: .1 }
      break
    case 'chest':
      response.pitch = kiss ? -12 : -8
      response.body.pitch = kiss ? -6 : -4
      response.body.roll = direction * 3
      response.face = { eyeOpen: kiss ? .65 : .85, smile: kiss ? .16 : .06, gazeY: -.4 }
      break
    case 'arm': case 'hand':
      response.yaw = side * (kiss ? 9 : 5)
      response.body.yaw = side * (family === 'hand' ? 3 : 6)
      response.body.roll = side * (kiss ? 4 : 2)
      response.face = { gazeX: side * .3, gazeY: -.15, smile: kiss ? .3 : .14,
        eyeOpen: kiss ? .72 : .92 }
      break
    default:
      response.body.pitch = kiss ? -5 : soft ? -4 : -2
      response.face = { eyeOpen: kiss ? .72 : .9, smile: kiss ? .2 : .06 }
  }
  if (gesture === 'stroke-up') response.pitch += 2
  response.yaw *= scale
  response.pitch *= scale
  response.roll *= scale
  for (const key of Object.keys(response.body)) response.body[key] *= scale
  return response
}

export function touchExpressionCategory(name) {
  const n = String(name || '').toLowerCase().replace(/[\s_-]+/g, '')
  if (/(?:兽耳|獸耳|耳机|耳機|头饰|頭飾|衣服|服装|服裝|麦克风|麥克風|立麦|立麥|microphone|headphone|headset|accessor|costume|outfit|^麦$|^麥$)/i.test(n)) return 'appearance'
  if (/(?:吐舌|舌头|舌頭|tongue)/i.test(n)) return 'tongue'
  if (/(?:爱心|愛心|心形眼|hearteye|loveeye|heartshape)/i.test(n)) return 'heart'
  if (/(?:星星|星眼|星形眼|stareye|starryeye|sparkleeye)/i.test(n)) return 'star'
  if (/(?:脸红|臉紅|羞|blush|flush|shy|embarras)/i.test(n)) return 'blush'
  if (/(?:流泪|流淚|眼泪|眼淚|哭|tear|cry|sob)/i.test(n)) return 'tear'
  if (/(?:脸黑|臉黑|黑脸|黑臉|阴影脸|陰影臉|暗脸|暗臉|darkface|shadowface|gloom)/i.test(n)) return 'dark'
  if (/(?:惊|驚|shock|surpris|startle|慌|pout|鼓嘴)/i.test(n)) return 'surprise'
  if (/(?:微笑|笑|开心|開心|happy|smil|wink|relax|calm|warm)/i.test(n)) return 'smile'
  return 'other'
}

/** No emotion roulette. No fallback from a missing mild smile to heart eyes,
 * tears, tongue or accessories. Optional authored channels enrich the pose.
 */
export function touchExpressionFor(region, names = [], ordinal = 0, gesture = 'tap', speaking = false, state = {}) {
  const available = names.filter(name => typeof name === 'string' && name.trim())
  const first = kinds => {
    for (const kind of kinds) {
      const name = available.find(n => touchExpressionCategory(n) === kind)
      if (name) return name
    }
    return null
  }
  const shy = state.shyness || 0
  if (isImpact(gesture)) {
    const impact = state.impact || 0
    return first(impact >= 72 ? ['dark', 'tear', 'surprise']
      : impact >= 20 ? ['tear', 'surprise'] : ['surprise'])
  }
  const family = touchFamily(region)
  if (gesture === 'kiss' && shy >= 86 &&
      ['mouth', 'neck', 'throat', 'collarbone'].includes(family)) return first(['heart', 'blush', 'smile'])
  if (shy >= 60 && ['nose', 'beast-ear'].includes(family) && gesture !== 'kiss')
    return first(['star', 'blush', 'smile'])
  return first(shy >= 14 ? ['blush', 'smile'] : ['smile'])
}

export function touchExpressionWeight(name, state = {}) {
  switch (touchExpressionCategory(name)) {
    case 'blush': return Math.min(.9, Math.max(.08, (state.shyness || 0) / 115))
    case 'heart': case 'star': return Math.min(1, Math.max(.45, (state.shyness || 0) / 100))
    case 'tear': return Math.min(.85, Math.max(.16, (state.impact || 0) / 85))
    case 'dark': return Math.min(.8, (state.impact || 0) / 120)
    default: return .55
  }
}

/** Combine only a real independent additive blush with high-tier eyes. */
export function touchCompanionOverlayFor(primary, names = [], metadata = {}, gesture = '', state = {}) {
  if (!['heart', 'star'].includes(touchExpressionCategory(primary)) ||
      isImpact(gesture) || (state.shyness || 0) < 60) return null
  const candidate = names.find(name => name !== primary && touchExpressionCategory(name) === 'blush')
  if (!candidate) return null
  const primaryParams = metadata[primary]?.Parameters
  const extraParams = metadata[candidate]?.Parameters
  if (!Array.isArray(primaryParams) || !Array.isArray(extraParams) || extraParams.length !== 1) return null
  const extra = extraParams[0]
  const id = String(extra?.Id || '')
  const value = Number(extra?.Value)
  if (!/^[A-Za-z][A-Za-z0-9_]{0,60}$/.test(id) ||
      primaryParams.some(p => p.Id === id) || extra.Blend !== 'Add' ||
      !Number.isFinite(value) || value <= 0 || value > 1) return null
  return { name: candidate, id, value: value * touchExpressionWeight(candidate, state) }
}
