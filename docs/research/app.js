for(const form of document.querySelectorAll('.filters')){
 const section=form.closest('section'),items=[...section.querySelectorAll(form.dataset.items||'.entry')],output=form.querySelector('output'),empty=section.querySelector('.empty');
 const apply=()=>{const data=new FormData(form),q=(data.get('query')||'').trim().toLocaleLowerCase(),tag=data.get('tag')||'',year=data.get('year')||'';let count=0;for(const item of items){const visible=(!q||item.textContent.toLocaleLowerCase().includes(q))&&(item.dataset.awards?JSON.parse(item.dataset.awards).some(([y,r])=>(!year||year===y)&&(!tag||tag===r)):(!tag||(item.dataset.tag||'').split(' ').includes(tag)));item.hidden=!visible;if(visible)count++;}output.textContent=`전체 ${items.length}개 중 ${count}개 표시`;empty.hidden=count>0;};
 form.addEventListener('submit',e=>e.preventDefault());form.addEventListener('input',apply);form.addEventListener('change',apply);form.addEventListener('reset',()=>setTimeout(apply,0));window.addEventListener('beforeprint',()=>items.forEach(x=>x.hidden=false));window.addEventListener('afterprint',apply);apply();
}

// A direct link from the catalog or index must reveal a profile hidden by a filter.
function revealProfile(){const id=decodeURIComponent(location.hash.slice(1)),target=document.getElementById(id);if(!target?.matches('.app-profile'))return;const form=target.closest('section')?.querySelector('.filters');if(form&&target.hidden){form.reset();setTimeout(()=>target.scrollIntoView({behavior:'auto'}),10);}}
window.addEventListener('hashchange',revealProfile);revealProfile();
document.addEventListener('click',event=>{if(event.target.closest('a[href^="#"]'))setTimeout(revealProfile,0);});
