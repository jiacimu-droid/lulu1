const test=require('node:test'),assert=require('node:assert/strict');
const pad=require('../world-joystick.js');
test('native center has a deadzone',()=>{assert.equal(pad.direction(72,72,144,144),null);assert.equal(pad.direction(88,82,144,144),null)});
test('native dominant axis: right/left/up/down',()=>{assert.deepEqual(pad.direction(140,72,144,144),[1,0]);assert.deepEqual(pad.direction(4,72,144,144),[-1,0]);assert.deepEqual(pad.direction(72,4,144,144),[0,-1]);assert.deepEqual(pad.direction(72,140,144,144),[0,1]);});
test('drag changes direction without releasing',()=>{assert.deepEqual(pad.direction(140,80,144,144),[1,0]);assert.deepEqual(pad.direction(77,140,144,144),[0,1]);});
test('tie favors horizontal just like Kotlin',()=>assert.deepEqual(pad.direction(110,110,144,144),[1,0]));
test('invalid viewport cannot move',()=>{assert.equal(pad.direction(40,40,0,0),null);assert.equal(pad.direction(NaN,10,144,144),null)});
test('thumb clamped to joystick radius',()=>{const [x,y]=pad.knobOffset(999,72,144,144);assert.equal(Math.round(x),36);assert.equal(y,0)});
