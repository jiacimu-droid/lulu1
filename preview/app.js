const screens=[...document.querySelectorAll('.screen')];
const historyStack=['desktop'];

function showScreen(id,{push=true}={}){
  const target=document.getElementById(id);
  if(!target)return;
  screens.forEach(screen=>screen.classList.toggle('active',screen===target));
  if(push&&historyStack.at(-1)!==id)historyStack.push(id);
  window.scrollTo({top:0,behavior:'instant'});
}

document.addEventListener('click',event=>{
  const opener=event.target.closest('[data-open]');
  if(opener){showScreen(opener.dataset.open);return;}
  const back=event.target.closest('[data-back]');
  if(back){
    if(historyStack.length>1)historyStack.pop();
    showScreen(historyStack.at(-1)||'desktop',{push:false});
  }
});

document.querySelectorAll('[data-segmented]').forEach(group=>{
  group.addEventListener('click',event=>{
    const button=event.target.closest('[data-panel]');
    if(!button)return;
    group.querySelectorAll('button').forEach(item=>item.classList.toggle('selected',item===button));
    document.querySelectorAll('.panel').forEach(panel=>panel.classList.toggle('active',panel.id===button.dataset.panel));
  });
});

document.querySelectorAll('.bottom-tabs button').forEach(button=>{
  button.addEventListener('click',()=>{
    document.querySelectorAll('.bottom-tabs button').forEach(item=>item.classList.toggle('selected',item===button));
    const labels={messages:'消息列表已保留',roles:'角色列表将在迁移角色数据后接入',moments:'朋友圈入口已预留',me:'“我的”会承接原桌面的个人内容'};
    const list=document.querySelector('.conversation-list');
    if(button.dataset.tab==='messages'){
      list.innerHTML='<button data-open="chat-detail"><span class="avatar">露</span><span><strong>露露</strong><small>我们继续把新的露露机做好吧～</small></span><time>刚刚</time></button><button><span class="avatar muted">角</span><span><strong>其他角色</strong><small>未来迁移角色数据后显示在这里</small></span><time>昨天</time></button>';
    }else{
      list.innerHTML=`<div class="placeholder"><h3>${button.textContent}</h3><p>${labels[button.dataset.tab]}</p></div>`;
    }
  });
});

const dateNode=document.getElementById('today');
if(dateNode){
  const now=new Date();
  dateNode.textContent=new Intl.DateTimeFormat('zh-CN',{month:'long',day:'numeric',weekday:'long'}).format(now);
}

window.addEventListener('popstate',()=>{
  if(historyStack.length>1)historyStack.pop();
  showScreen(historyStack.at(-1)||'desktop',{push:false});
});
