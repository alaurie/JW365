// WebAuthn polyfill for the JavaFX WebView, which ships no WebAuthn at all.
// Forwards navigator.credentials.get() to WebAuthnBridge (Java), which drives
// a USB security key through the fido2-tools CLI. Sign-in only; registering a
// key is not supported. Installed only on the Microsoft sign-in origins.
(function () {
  'use strict';
  if (window.PublicKeyCredential) return;
  var ALLOWED_ORIGINS = [
    'https://login.microsoftonline.com',
    'https://login.microsoft.com',
    'https://login.live.com'
  ];
  if (ALLOWED_ORIGINS.indexOf(location.origin) < 0) return;
  if (typeof window.jw365WebAuthn === 'undefined') return;

  function b64uEncode(buf) {
    var bytes = new Uint8Array(buf);
    var bin = '';
    for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function b64uDecode(s) {
    s = s.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var bin = atob(s);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes.buffer;
  }
  function toBuffer(v) {
    if (v instanceof ArrayBuffer) return v;
    if (ArrayBuffer.isView(v)) return v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength);
    return v;
  }
  function domError(name, message) {
    try { return new DOMException(message, name); }
    catch (e) { var err = new Error(message); err.name = name; return err; }
  }

  function AuthenticatorResponse() {}
  function AuthenticatorAssertionResponse() {}
  AuthenticatorAssertionResponse.prototype = Object.create(AuthenticatorResponse.prototype);
  function AuthenticatorAttestationResponse() {}
  AuthenticatorAttestationResponse.prototype = Object.create(AuthenticatorResponse.prototype);

  function PublicKeyCredential() {}
  PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = function () { return Promise.resolve(false); };
  PublicKeyCredential.isConditionalMediationAvailable = function () { return Promise.resolve(false); };
  PublicKeyCredential.getClientCapabilities = function () {
    return Promise.resolve({ conditionalGet: false, hybridTransport: false, userVerifyingPlatformAuthenticator: false });
  };
  PublicKeyCredential.prototype.getClientExtensionResults = function () { return {}; };
  PublicKeyCredential.prototype.toJSON = function () {
    return {
      id: this.id, rawId: this.id, type: this.type,
      authenticatorAttachment: this.authenticatorAttachment,
      clientExtensionResults: {},
      response: {
        clientDataJSON: b64uEncode(this.response.clientDataJSON),
        authenticatorData: b64uEncode(this.response.authenticatorData),
        signature: b64uEncode(this.response.signature),
        userHandle: this.response.userHandle ? b64uEncode(this.response.userHandle) : null
      }
    };
  };

  function buildCredential(r) {
    var response = Object.create(AuthenticatorAssertionResponse.prototype);
    Object.defineProperties(response, {
      clientDataJSON: { value: b64uDecode(r.clientDataJson), enumerable: true },
      authenticatorData: { value: b64uDecode(r.authenticatorData), enumerable: true },
      signature: { value: b64uDecode(r.signature), enumerable: true },
      userHandle: { value: r.userHandle ? b64uDecode(r.userHandle) : null, enumerable: true }
    });
    var cred = Object.create(PublicKeyCredential.prototype);
    Object.defineProperties(cred, {
      id: { value: r.credentialId, enumerable: true },
      rawId: { value: b64uDecode(r.credentialId), enumerable: true },
      type: { value: 'public-key', enumerable: true },
      authenticatorAttachment: { value: 'cross-platform', enumerable: true },
      response: { value: response, enumerable: true }
    });
    return cred;
  }

  function get(options) {
    var pk = options && options.publicKey;
    if (!pk) return Promise.reject(new TypeError('options.publicKey is required'));
    if (options.mediation === 'conditional') {
      return Promise.reject(domError('NotAllowedError', 'Conditional mediation is not supported.'));
    }
    if (options.signal && options.signal.aborted) return Promise.reject(domError('AbortError', 'Aborted.'));
    var request = {
      origin: location.origin,
      rpId: pk.rpId || location.hostname,
      challenge: b64uEncode(toBuffer(pk.challenge)),
      allowCredentials: (pk.allowCredentials || []).map(function (c) { return b64uEncode(toBuffer(c.id)); }),
      userVerification: pk.userVerification || 'preferred',
      timeout: pk.timeout || 0
    };
    return new Promise(function (resolve, reject) {
      window.jw365WebAuthn.getAssertion(JSON.stringify(request), function (resultJson) {
        var r;
        try { r = JSON.parse(resultJson); }
        catch (e) { reject(domError('NotAllowedError', 'Invalid response from the security key bridge.')); return; }
        if (r.error) reject(domError(r.error, r.message));
        else resolve(buildCredential(r));
      });
    });
  }

  function create() {
    return Promise.reject(domError('NotSupportedError',
      'Registering a security key is not supported here. Register it in a regular browser, then sign in.'));
  }

  window.PublicKeyCredential = PublicKeyCredential;
  window.AuthenticatorResponse = AuthenticatorResponse;
  window.AuthenticatorAssertionResponse = AuthenticatorAssertionResponse;
  window.AuthenticatorAttestationResponse = AuthenticatorAttestationResponse;
  Object.defineProperty(navigator, 'credentials', {
    value: {
      get: get,
      create: create,
      store: function () { return Promise.reject(domError('NotSupportedError', 'Not supported.')); },
      preventSilentAccess: function () { return Promise.resolve(); }
    },
    configurable: true
  });
})();
