/* Mirrors GameSceneDeck.kt gameJoystickDirection's dead zone / dominant axis.
 * The browser is a visual-fixture scene; the formal Android rules still own moves. */
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;root.LuluWorldJoystick=api;})(typeof globalThis==='object'?globalThis:this,function(){
  'use strict';
  function direction(x,y,width,height){
    if(![x,y,width,height].every(Number.isFinite)||width<=0||height<=0)return null;
    const dx=x-width/2,dy=y-height/2,dead=Math.min(width,height)*.155;
    if(Math.max(Math.abs(dx),Math.abs(dy))<dead)return null;
    return Math.abs(dx)>=Math.abs(dy)?[dx>=0?1:-1,0]:[0,dy>=0?1:-1];
  }
  function knobOffset(x,y,width,height){
    if(![x,y,width,height].every(Number.isFinite)||width<=0||height<=0)return [0,0];
    let dx=x-width/2,dy=y-height/2;
    const limit=Math.min(width,height)*.25,dist=Math.hypot(dx,dy);
    if(dist>limit){dx*=limit/dist;dy*=limit/dist}
    return [dx,dy];
  }
  return Object.freeze({direction,knobOffset});
});
