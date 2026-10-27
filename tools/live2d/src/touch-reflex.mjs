import { isImpact, touchFamily, touchForce } from './touch-response.mjs'

const PALM_IMPACT = Object.freeze({
  hair: [7, -13, 8, 3], forehead: [10, -11, 8, 3],
  'beast-ear': [14, -5, 10, 3], ear: [21, -6, 14, 4],
  eye: [12, -6, 8, 3], nose: [4, -12, 3, 4],
  cheek: [12, -4, 23, 5], mouth: [12, -8, 14, 4],
  neck: [7, -12, 6, 6], throat: [4, -13, 4, 7],
  collarbone: [5, -8, 4, 8], chest: [5, -5, 4, 10],
  arm: [7, -3, 5, 5], hand: [5, -2, 3, 3], body: [5, -5, 4, 9],
})

// A face reflex needs a matching torso response. Previously most soft touch
// regions only animated eyebrows/eyes/head while body yaw/pitch/roll stayed
// exactly zero, so the on-screen character appeared glued in place.
// These are modest upper-body angles, not fabricated arm/shoulder motions.
const BODY_FOLLOW = Object.freeze({
  hair: [1.4, 3.8, 2.8], forehead: [1, 3.2, 2.2],
  'beast-ear': [2.6, -1.6, 3.2], ear: [2.6, -1.8, 3.4],
  eye: [1.5, -2.5, 1.8], nose: [1.2, -3.2, 1.8],
  cheek: [4.5, 1.2, -4.2], mouth: [2, -3.2, 2.2],
  neck: [2.8, -3.2, 4.4], throat: [1.4, -2.5, 2.2],
  collarbone: [2, -2.2, 3], chest: [1.6, -2.2, 3.2],
  arm: [3.2, -1, 4], hand: [2.6, -1, 3.6],
  body: [2.5, -2.4, 3.5],
})

/** All axes are optional actuators, validated against the imported rig.
 * Region × tool × force × elapsed time; ordinal only varies tiny mannerisms.
 * It never rotates emotions or bypasses the gradual visual response state.
 */
// A held impact peak, then a smooth return with ONE small spring overshoot.
// Absolute contact time makes the sequence identical at different render FPS.
export function impactEnvelope(elapsedMs) {
  const t = Math.max(0, elapsedMs)
  // Reach the sideways peak in ~90ms, not an instant teleport or a 420ms
  // rigid freeze. A longer 1.5s release lets the head come back naturally.
  if (t < 90) return Math.sin(t / 90 * Math.PI / 2)
  if (t < 250) return 1
  if (t < 1750) return .5 + .5 * Math.cos(Math.PI * (t - 250) / 1500)
  if (t < 2300) return -.035 * Math.sin(Math.PI * (t - 1750) / 550)
  return 0
}

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
    yaw: 0, pitch: 0, roll: 0, impactMotion: false,
    body: { yaw: 0, pitch: 0, roll: 0 }, face: {}, parameters: {},
  }
  // The imported rig's eyes share their blink reflex. Closing only the
  // touched eye while the opposite one stays wide open looks like a broken
  // eyelid, particularly on eye taps or impacts.
  const closeEyesTogether = value => { response.face.eyeOpen = value }
  if (isImpact(gesture)) {
    const recoil = impactEnvelope(elapsedMs)
    const swipe = direction || (family === 'cheek' && side !== 0 ? side : -side || -1)
    const stationary = direction === 0
    const contactGain = stationary ? .58 : 1
    const headHit = ['hair', 'forehead', 'beast-ear', 'ear', 'eye',
      'nose', 'cheek', 'mouth'].includes(family)
    const faceHit = ['forehead', 'eye', 'nose', 'cheek', 'mouth'].includes(family)
    const [yaw, pitch, roll, torso] = PALM_IMPACT[family] || PALM_IMPACT.body
    const follow = .15 + .85 * Math.min(1, Math.max(0, elapsedMs - 100) / 380)
    // A cheek swipe bends the HEAD around the neck; touching the chest or
    // arm never throws the whole face/body sideways. Stationary taps register
    // a much smaller local jolt than a lateral finger sweep.
    response.impactMotion = headHit
    response.impactFace = faceHit
    if (headHit) {
      response.yaw = swipe * yaw * .17 * scale * recoil * contactGain
      response.roll = swipe * Math.min(28, roll * (.36 + .8 * force)) *
        recoil * contactGain
      response.pitch = pitch * .38 * scale * recoil * contactGain
      // The torso only yields slightly AFTER the neck. Never rotate the
      // entire model for a cheek impact.
      response.body = {
        yaw: swipe * torso * .08 * scale * recoil * follow * contactGain,
        pitch: -torso * .12 * scale * recoil * follow * contactGain,
        roll: swipe * torso * .16 * scale * recoil * follow * contactGain,
      }
    } else {
      // Upper-body contact is a small local flinch, not a slap to the face.
      response.yaw = 0
      response.roll = 0
      response.pitch = 0
      response.body = {
        yaw: 0,
        pitch: Math.min(2.2, torso * .17 * scale) * recoil * contactGain,
        roll: swipe * Math.min(1.7, torso * .11 * scale) * recoil * contactGain,
      }
    }
    const startled = elapsedMs < 220
    response.face = startled
      ? { eyeOpen: 1, browY: .48, browAngle: .08, smile: -.12, pout: .08 }
      : { eyeOpen: .78 + .12 * (1 - force), eyeSmile: 0,
          browY: .34 + force * .22, browAngle: .25,
          browForm: .22, smile: -.44, pout: .34 + force * .24,
          gazeX: -swipe * .35 * Math.max(0, 1 - (elapsedMs - 220) / 1600),
          gazeY: -.16 }
    if (family === 'eye') closeEyesTogether(elapsedMs < 200 ? .18 : .52)
    if (family === 'cheek' || family === 'mouth') {
      response.face.cheekPuff = startled ? .04 : Math.min(.32, force * .22)
      response.face.jawOpen = startled ? .12 : 0
      response.face[side < 0 ? 'browLeftY' : 'browRightY'] =
        startled ? .45 : .32
    }
    if (family === 'beast-ear') {
      response.parameters[side < 0 ? 'se_wuli_x_l' : 'se_wuli_x_r'] =
        -.55 * scale * recoil
    }
    return response
  }

  const kiss = gesture === 'kiss'
  const stroking = gesture.startsWith('stroke') || gesture === 'hold'
  const soft = stroking || kiss
  // The same pet is not a permanent squint. Alongside accumulated shyness,
  // real contact direction/side and touch ordinal choose different visible
  // eye and brow articulations, without inventing an emotion from random data.
  const receptiveEyes = Math.max(.48, [.94, .68, .84][variation] - shy * .13)
  switch (family) {
    case 'hair': case 'forehead':
      response.pitch = kiss ? -3 : soft ? 10 + variation * 2 : 4
      response.yaw = direction * 7
      response.roll = (direction || (variation === 1 ? -1 : 1)) * (kiss ? 3 : 4)
      response.face = { eyeOpen: kiss ? .42 + variation * .16 : soft ? receptiveEyes : .9,
        eyeSmile: kiss ? .24 : .2, smile: kiss ? .22 : .16 + variation * .06,
        browY: soft ? .06 + shy * .1 : 0 }
      break
    case 'beast-ear':
      response.yaw = -side * (kiss ? 9 : 5)
      response.pitch = kiss ? -4 : 5
      response.roll = side * (kiss ? 9 : 6)
      response.face = { eyeOpen: kiss ? .48 + variation * .11 : receptiveEyes,
        smile: .15 + shy * .15, browY: variation === 0 ? .16 : -.04 }
      response.parameters[side < 0 ? 'se_wuli_x_l' : 'se_wuli_x_r'] =
        (kiss ? -.52 : .28 + .16 * Math.sin(elapsedMs / 120)) * scale
      break
    case 'ear':
      response.yaw = -side * (kiss ? 12 : 7)
      response.roll = side * (kiss ? 11 : 7)
      response.pitch = kiss ? -4 : 2
      response.body.roll = -side * 2
      response.face = { eyeOpen: kiss ? .5 : receptiveEyes, browY: .12,
        smile: kiss ? .13 : .14 + variation * .07, gazeX: side * .25 }
      break
    case 'eye':
      response.yaw = -side * (kiss ? 3 : 6)
      response.pitch = kiss ? -1 : -3
      response.face = { eyeOpen: .9, eyeSmile: kiss ? .2 : 0, smile: kiss ? .2 : .05 }
      closeEyesTogether(kiss ? .24 : .42)
      break
    case 'nose':
      response.pitch = kiss ? 5 : -5
      response.face = { eyeOpen: kiss ? .67 : receptiveEyes, smile: kiss ? .3 : .16,
        browY: kiss ? .1 : .18 }
      break
    case 'cheek':
      response.yaw = side * (kiss ? 5 : 9 + variation)
      response.roll = -side * (kiss ? 8 : 5)
      response.pitch = kiss ? -2 - shy * 6 : 6
      response.face = { eyeOpen: kiss ? .8 - shy * .2 : receptiveEyes,
        eyeSmile: kiss ? .15 : .12 + variation * .08,
        smile: kiss ? .2 + shy * .2 : .14 + variation * .08,
        gazeX: kiss ? -side * .3 : side * .15,
        browY: variation === 2 ? .16 : -.04,
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
  // Exploit independently modeled brows and cheek puff instead of playing
  // the same squint on every touch. No synthetic mesh deformation: cheek
  // pulling needs a real authored deformer and is deliberately out of scope.
  if (family === 'cheek' && !kiss) {
    response.face.cheekPuff = Math.min(.62,
      (.16 + force * .35 + shy * .1) * (stroking ? 1 : .75))
    response.face[side < 0 ? 'browLeftY' : 'browRightY'] =
      variation === 1 ? -.15 : .22
    response.face.pout = variation === 2 ? .16 : .06
  } else if (family === 'nose' && !kiss) {
    response.face.browLeftY = .24
    response.face.browRightY = .12
    response.face.cheekPuff = variation === 1 ? .18 : .05
  } else if ((family === 'hair' || family === 'forehead') && soft) {
    response.face.browLeftY = variation === 0 ? .16 : -.08
    response.face.browRightY = variation === 2 ? .16 : .03
  } else if ((family === 'ear' || family === 'beast-ear') && !kiss) {
    response.face[side < 0 ? 'browLeftY' : 'browRightY'] = .26
  } else if (family === 'mouth' && !kiss) {
    response.face.cheekPuff = variation === 0 ? .35 : .13
    response.face.pout = .25
  }
  // Couple the real torso rig to each local touch. Mirror left/right and
  // follow the travel direction for central zones. Do not perform an authored
  // full-body motion: imported rigs with no torso parameters simply skip it.
  const [followYaw, followPitch, followRoll] = BODY_FOLLOW[family] || BODY_FOLLOW.body
  const followSide = side || direction || (variation === 1 ? -1 : 1)
  const followStrength = kiss ? 1.15 : stroking ? 1 : .75
  response.body.yaw += followSide * followYaw * followStrength
  response.body.pitch += followPitch * followStrength
  response.body.roll += followSide * followRoll * followStrength
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
    // This is the *local visual reflex*, not a decision to become angry.
    // If a real tear exp3 exists use it, including after repeated strong hits.
    // Never substitute a black-face/shadow exp3 for aggrieved crying.
    return first(impact >= 12 ? ['tear', 'surprise'] : ['surprise', 'tear'])
  }
  const family = touchFamily(region)
  if (gesture === 'kiss' && shy >= 86 &&
      ['mouth', 'neck', 'throat', 'collarbone'].includes(family)) return first(['star', 'blush', 'smile'])
  if (shy >= 60 && ['nose', 'beast-ear'].includes(family) && gesture !== 'kiss')
    return first(['star', 'blush', 'smile'])
  return first(shy >= 14 ? ['blush', 'smile'] : ['smile'])
}

export function touchExpressionWeight(name, state = {}) {
  switch (touchExpressionCategory(name)) {
    case 'blush': return Math.min(.9, Math.max(.08, (state.shyness || 0) / 115))
    case 'heart': case 'star': return Math.min(1, Math.max(.45, (state.shyness || 0) / 100))
    case 'tear': return Math.min(1, Math.max(.55, (state.impact || 0) / 62))
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

/**
 * Deterministic touch-to-motion wiring for the wolf pack: a completed
 * touch plays one catalog clip, never a model decision. The map is keyed
 * by touch family (side suffix stripped) and gesture kind. Side only
 * picks between the mirrored tilt pair; hair follows the stroke
 * direction because it has no side. Anything unmapped returns null.
 */
export function touchMotionFor(region, gesture) {
  const family = touchFamily(String(region || ''))
  const side = String(region || '').endsWith('-left') ? -1
    : String(region || '').endsWith('-right') ? 1 : 0
  const kind = String(gesture || '')
  if (kind === 'tap') {
    if (family === 'chest') return 17
    if (family === 'nose') return 23
    return null
  }
  if (kind.startsWith('stroke')) {
    if (family === 'beast-ear') return side < 0 ? 1 : 2
    if (family === 'hair') return kind === 'stroke-right' ? 2 : 1
    if (family === 'forehead') return 26
    if (family === 'cheek') return 28
    return null
  }
  if (/^slap(-left|-right)?$/.test(kind)) {
    if (family === 'cheek') return 9
    return null
  }
  return null
}
