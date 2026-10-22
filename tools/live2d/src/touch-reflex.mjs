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
  const variant = Math.abs(ordinal) % 3
  const gentle = stroking || gesture === 'hold'
  const eyeFace = gentle
    ? { eyeOpen: variant === 1 ? .38 : .58, eyeSmile: .36 }
    : { eyeOpen: .78, eyeSmile: .12 }
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
      response.face = { ...eyeFace, smile: gentle ? .22 : .08 }
      break
    case 'ear-left': case 'ear-right':
      response.yaw = -side * (10 + variant * 2)
      response.roll = side * 6
      response.pitch = gentle ? 4 : -3
      response.face = { eyeOpen: .68, browY: .16 }
      break
    case 'eye-left': case 'eye-right':
      response.yaw = -side * 7
      response.pitch = -3
      response.face = { eyeOpen: gentle ? .17 : .4 }
      break
    case 'nose':
      response.pitch = -5
      response.face = { smile: .25, eyeSmile: .27 }
      break
    case 'cheek-left': case 'cheek-right':
      response.yaw = side * (8 + variant * 3)
      response.roll = -side * 7
      response.pitch = gentle ? 7 : 2
      response.face = { ...eyeFace, smile: .32 }
      break
    case 'mouth':
      response.pitch = -7
      response.face = { eyeOpen: .72, browY: .1 }
      break
    case 'neck':
      response.yaw = direction * 7 || (variant ? 7 : -7)
      response.roll = (side || 1) * 5
      response.body.roll = -response.roll * .35
      response.face = { eyeOpen: .68 }
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

export function touchExpressionFor(region, names = [], ordinal = 0) {
  const candidates = names.filter(name =>
    /(?:smile|happy|relax|shy|blush|wink|笑|微笑|眯眼|眯眼|闭眼|閉眼|害羞|温柔|溫柔)/i.test(name) &&
    !/(?:tongue|吐舌|anger|生气|生氣|cry|哭)/i.test(name))
  if (!candidates.length) return null
  const pick = (ordinal + (region.includes('cheek') ? 1 : 0)) % candidates.length
  return candidates[pick]
}
