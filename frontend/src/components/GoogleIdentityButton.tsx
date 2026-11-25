import { useEffect, useState } from 'react';
import { GoogleLogin, GoogleOAuthProvider, useGoogleOAuth } from '@react-oauth/google';
import { useQuery } from '@tanstack/react-query';
import { api } from '../lib/api';

export function useGoogleOptions() {
  return useQuery({
    queryKey: ['auth-options'],
    queryFn: () => api<{googleClientId: string}>('/auth/options'),
    staleTime: 5 * 60_000,
  });
}

type ButtonProps = {
  text: 'signin_with' | 'signup_with' | 'continue_with';
  onSuccess: (credential?: string) => void;
  onError: () => void;
};

export function GoogleIdentityButton({ clientId, ...props }: ButtonProps & {clientId: string}) {
  const [failed, setFailed] = useState(false);
  return <GoogleOAuthProvider clientId={clientId} onScriptLoadError={() => setFailed(true)}>
    <IdentityButton {...props} failed={failed}/>
  </GoogleOAuthProvider>;
}

function IdentityButton({ text, onSuccess, onError, failed }: ButtonProps & {failed: boolean}) {
  const { scriptLoadedSuccessfully } = useGoogleOAuth();
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (scriptLoadedSuccessfully) return;
    const timer = setTimeout(() => setSlow(true), 8000);
    return () => clearTimeout(timer);
  }, [scriptLoadedSuccessfully]);

  if (!scriptLoadedSuccessfully) {
    return <p className="fineprint" role="status">{failed || slow
      ? 'Google sign-in could not load. Try refreshing the page or use email below if available.'
      : 'Loading Google sign-in…'}</p>;
  }
  return <GoogleLogin text={text} shape="rectangular" width="244"
    onSuccess={result => onSuccess(result.credential)} onError={onError}/>;
}
