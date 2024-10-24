import { NativeEventEmitter } from 'react-native';
declare const fetch: (url: String, obj: any) => Promise<any>;
declare const getCookies: (domain: string) => any;
declare const removeCookieByName: (name: string) => any;
declare const closeWebSocket: () => void;
declare const sendWebSocketMessage: (message: any) => Promise<any>;
declare const eventEmitter: NativeEventEmitter;
export { fetch, getCookies, removeCookieByName, sendWebSocketMessage, eventEmitter, closeWebSocket, };
//# sourceMappingURL=index.d.ts.map