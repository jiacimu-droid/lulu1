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

export function touchExpressionFor(region, names = [], ordinal = 0, gesture = 'tap') {
  // Keep actual exp3 variety rather than filtering down to the same closed-eye
  // animation. Speech owns tongue/mouth-exclusive expressions separately.
  const safe = names.filter(name =>
    name && !/(?:tongue|吐舌|anger|生气|生氣|cry|哭|痛|hurt|injur)/i.test(name))
  if (!safe.length) return null
  const preferences = gesture === 'kiss'
    ? /(?:kiss|love|shy|blush|wink|喜|害羞|脸红|臉紅|笑|甜)/i
    : gesture === 'kick' || gesture.startsWith('slap')
      ? /(?:surpris|shock|awkward|startle|驚|惊|慌|愣|鼓嘴|pout)/i
      : /(?:smile|happy|relax|shy|blush|wink|laugh|calm|笑|眯眼|闭眼|閉眼|害羞|温柔|溫柔|眨|星|开心|開心)/i
  const matched = safe.filter(name => preferences.test(name))
  const candidates = matched.length ? matched : safe
  return candidates[(Math.abs(ordinal) + (region.includes('cheek') ? 1 : 0)) % candidates.length]
}
