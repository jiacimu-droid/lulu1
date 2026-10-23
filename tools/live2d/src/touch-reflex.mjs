/**
 * Fast local tactile actuation; NEVER infers the character's wishes or
 * injects a second paid decision. Each returned parameter is optional and
 * validated against the imported Cubism rig before it can affect a frame.
 */
export function touchReflexFor(region, gesture, ordinal = 0) {
  const stroking = gesture.startsWith('stroke')
  const side = region.endsWith('-left') ? -1 : region.endsWith('-right') ? 1 : 0
  const direction = gesture === 'stroke-left' ? -1
    : gesture === 'stroke-right' ? 1 : 0
  const up = gesture === 'stroke-up'
  const variant = Math.abs(ordinal) % 5
  const gentle = stroking || gesture === 'hold' || gesture === 'kiss'
  const eyeFace = gentle
    ? { eyeOpen: [.92, .48, .74, .34, .83][variant],
        eyeSmile: [.1, .42, .28, .57, .24][variant],
        smile: [.25, .38, .12, .46, .3][variant] }
    : { eyeOpen: .85, eyeSmile: .14, smile: .17 }
  // Tool-specific physical reflexes are not claims about feelings or consent.
  // A cheek kiss, playful foot nudge and palm swat have different motion arcs.
  if (gesture === 'kiss') return {
    yaw: side * 5, pitch: 6, roll: -side * 8,
    body: { yaw: 0, pitch: 2, roll: -side * 2 },
    face: { eyeOpen: variant % 2 ? .48 : .9, eyeSmile: .47,
      smile: .55, cheekPuff: .25 },
  }
  if (gesture === 'kick') return {
    yaw: -side * 13, pitch: -7, roll: (side || 1) * 13,
    body: { yaw: -side * 7, pitch: -8, roll: (side || 1) * 6 },
    face: { eyeOpen: .94, browY: .4, pout: variant % 2 ? .33 : 0 },
  }
  if (gesture === 'slap-left' || gesture === 'slap-right') {
    const swipe = gesture === 'slap-right' ? 1 : -1
    return {
      yaw: swipe * 19, pitch: -3, roll: swipe * 15,
      body: { yaw: swipe * 4, pitch: 0, roll: swipe * 4 },
      face: { eyeOpen: .89, browY: .39, smile: variant % 2 ? .1 : -.25 },
    }
  }
  const response = {
    yaw: 0, pitch: 0, roll: 0,
    body: { yaw: 0, pitch: 0, roll: 0 },
    face: {},
  }
  switch (region) {
    case 'hair': case 'forehead':
      response.pitch = gentle ? 10 + variant * 2 : 4
      response.yaw = direction * 8
      response.roll = (direction || (variant % 2 ? -1 : 1)) * 4
      response.face = { ...eyeFace, smile: gentle ? eyeFace.smile : .08 }
      break
    case 'ear-left': case 'ear-right':
      response.yaw = -side * (7 + variant * 2)
      response.roll = side * (9 + variant)
      response.pitch = gentle ? 4 : -3
      response.face = { eyeOpen: variant % 2 ? .8 : .58,
        browY: .16, smile: variant % 2 ? .26 : .08 }
      break
    case 'eye-left': case 'eye-right':
      response.yaw = -side * 7
      response.pitch = -3
      response.face = { eyeOpen: gentle ? .45 : .8, squint: gentle ? .1 : 0 }
      break
    case 'nose':
      response.pitch = -5
      response.face = { smile: .25, eyeSmile: variant % 2 ? .38 : .1 }
      break
    case 'cheek-left': case 'cheek-right':
      response.yaw = side * (8 + variant * 3)
      response.roll = -side * 7
      response.pitch = gentle ? 7 : 2
      response.face = { ...eyeFace, smile: eyeFace.smile }
      break
    case 'mouth':
      response.pitch = -7
      response.face = { eyeOpen: .84, browY: .1, smile: variant % 2 ? .35 : .12 }
      break
    case 'neck':
      response.yaw = direction * 7 || (variant ? 7 : -7)
      response.roll = (side || 1) * 5
      response.body.roll = -response.roll * .35
      response.face = { eyeOpen: .76, smile: .18 }
      break
    case 'collarbone':
      response.pitch = -7
      response.body.pitch = -3
      response.face = { eyeOpen: .75 }
      break
    case 'chest':
      response.pitch = -8
      response.body.pitch = -6
      response.body.roll = direction * 3
      break
    case 'arm-left': case 'arm-right':
    case 'hand-left': case 'hand-right':
      response.yaw = side * 5
      response.body.yaw = side * (5 + variant * 2)
      response.body.roll = side * 3
      break
    default:
      response.body.pitch = gentle ? -4 : -2
  }
  if (up) response.pitch += 3
  // Returning a new object on each event means springs can smoothly follow
  // the changing fingertip, rather than replaying a canned animation.
  return response
}

/**
 * Runtime catalogue of real .exp3 resources. Never create a visual expression
 * that the imported model did not actually provide. Appearance switches (ears,
 * headphones, microphone) are available in the manual expression picker, but
 * are NOT automatically toggled by an accidental cheek touch.
 */
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

/**
 * Every touch advances a predictable category rotation. Gentle interactions
 * prefer affection/playful eyes; playful hits can show surprise, tears or dark
 * face. Unknown expressive .exp3 files remain reachable as fallbacks instead
 * of being silently discarded by a tiny keyword allow-list. Accessories never
 * auto-activate. Calling with speaking=true excludes tongue, not blush/hearts.
 */
export function touchExpressionFor(region, names = [], ordinal = 0, gesture = 'tap', speaking = false) {
  const available = [...new Set(names.filter(name => typeof name === 'string' && name.trim()))]
    .map(name => ({ name, category: touchExpressionCategory(name) }))
    .filter(item => item.category !== 'appearance' && (!speaking || item.category !== 'tongue'))
  if (!available.length) return null
  const impact = gesture === 'kick' || gesture.startsWith('slap')
  const affection = gesture === 'kiss'
  const curious = region === 'nose' || region.includes('ear') || region.includes('eye')
  const preference = impact
    ? ['dark', 'surprise', 'tear', 'blush', 'other', 'smile', 'star', 'heart', 'tongue']
    : affection
      ? ['heart', 'blush', 'star', 'smile', 'other', 'tongue', 'surprise', 'tear', 'dark']
      : curious
        ? ['star', 'blush', 'smile', 'heart', 'other', 'tongue', 'surprise', 'tear', 'dark']
        : ['blush', 'smile', 'heart', 'star', 'other', 'tongue', 'surprise', 'tear', 'dark']
  // Organize by category so model filenames and filesystem ordering cannot
  // accidentally make the same emotion the only one ever displayed.
  const pools = preference.map(kind => available.filter(item => item.category === kind))
    .filter(group => group.length)
  const offset = Math.abs(Number.isFinite(ordinal) ? Math.trunc(ordinal) : 0)
  // A cycle visits each distinct expression before repeating, while its order
  // depends on the kind of interaction. Body gestures are not emotions.
  const ordered = pools.flatMap(group => group.map(item => item.name))
  return ordered[offset % ordered.length] || null
}

/**
 * Opt-in second authored facial layer for a real model that has independent
 * additive expression channels (e.g. wolf bq4 blush + bq6 heart eyes).
 * Never merge outfit switches or conflicting parameters. No model assets are
 * redistributed: metadata is read from the imported exp3 files on-device.
 */
export function touchCompanionOverlayFor(primary, names = [], metadata = {}, gesture = '') {
  const primaryKind = touchExpressionCategory(primary)
  if (!['heart', 'star', 'blush'].includes(primaryKind)) return null
  const nextKind = primaryKind === 'blush'
    ? (gesture === 'kiss' ? 'heart' : 'star') : 'blush'
  const candidate = names.find(name =>
    name !== primary && touchExpressionCategory(name) === nextKind)
  if (!candidate) return null
  const primaryParams = metadata[primary]?.Parameters
  const extraParams = metadata[candidate]?.Parameters
  if (!Array.isArray(primaryParams) || !Array.isArray(extraParams) ||
      extraParams.length !== 1) return null
  const extra = extraParams[0]
  const id = String(extra?.Id || '')
  const value = Number(extra?.Value)
  const occupied = new Set(primaryParams.map(param => String(param?.Id || '')))
  if (!/^[A-Za-z][A-Za-z0-9_]{0,60}$/.test(id) || occupied.has(id) ||
      extra.Blend !== 'Add' || !Number.isFinite(value) || value <= 0 || value > 1)
    return null
  return { name: candidate, id, value }
}
