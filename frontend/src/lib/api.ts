const BASE='/api/v1';
export function apiPath(path: string) {
  if (!path.startsWith("/") || path.startsWith("//")) throw new Error("Expected an API path");
  return path.startsWith(BASE + "/") ? path : BASE + path;
}
export class ApiFailure extends Error {constructor(public status:number,public code:string,message:string){super(message);}}
export function csrfToken(){return decodeURIComponent(document.cookie.split('; ').find(v=>v.startsWith('CF_CSRF='))?.slice(8)||'');}
export async function api<T=unknown>(path:string,options:RequestInit={}):Promise<T>{
  const method=(options.method||'GET').toUpperCase();
  const headers = new Headers(options.headers);
  if (!(options.body instanceof FormData) && options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (!['GET','HEAD','OPTIONS'].includes(method)) {
    const csrf = csrfToken();
    if (csrf) headers.set('X-CSRF-Token', csrf);
  }
  const res=await fetch(apiPath(path),{...options,method,headers,credentials:'include'});
  if (!res.ok) {
    const error: unknown = await res.json().catch(() => null);
    const problem = error && typeof error === 'object' ? error : {};
    const code = 'code' in problem && typeof problem.code === 'string' ? problem.code : 'HTTP_ERROR';
    const message = 'message' in problem && typeof problem.message === 'string'
      ? problem.message : `Request failed (${res.status})`;
    throw new ApiFailure(res.status, code, message);
  }
  if(res.status===204)return undefined as T;
  const type=res.headers.get('content-type')||'';
  return (type.includes('application/json')?await res.json():await res.blob()) as T;
}
export const post=<T=unknown>(path:string,body:unknown)=>api<T>(path,{method:'POST',body:JSON.stringify(body)});
export const put=<T=unknown>(path:string,body:unknown)=>api<T>(path,{method:'PUT',body:JSON.stringify(body)});
export const patch=<T=unknown>(path:string,body:unknown)=>api<T>(path,{method:'PATCH',body:JSON.stringify(body)});
export const del=<T=unknown>(path:string,body?:unknown)=>api<T>(path,{method:'DELETE',...(body?{body:JSON.stringify(body)}:{})});
export async function download(path: string, filename: string) {
  const anchor = document.createElement('a');
  anchor.href = apiPath(path);
  anchor.download = filename;
  anchor.hidden = true;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
}

