"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.sendWebSocketMessage = exports.removeCookieByName = exports.getCookies = exports.fetch = exports.eventEmitter = exports.closeWebSocket = void 0;
var _reactNative = require("react-native");
const LINKING_ERROR = `The package 'react-native-websocket-ssl-pinning' doesn't seem to be linked. Make sure: \n\n` + _reactNative.Platform.select({
  ios: "- You have run 'pod install'\n",
  default: ''
}) + '- You rebuilt the app after installing the package\n' + '- You are not using Expo Go\n';
const WebSocketSslPinning = _reactNative.NativeModules.WebSocketSslPinning ? _reactNative.NativeModules.WebSocketSslPinning : new Proxy({}, {
  get() {
    throw new Error(LINKING_ERROR);
  }
});
const fetch = async (url, obj) => {
  if (obj.headers) {
    obj.headers = Object.keys(obj.headers).reduce((acc, key) => ({
      ...acc,
      [obj.caseSensitiveHeaders ? key : key.toLowerCase()]: obj.headers[key]
    }), {});
  }
  try {
    const res = await new Promise((resolve, reject) => {
      WebSocketSslPinning.fetch(url, obj, (err, response) => {
        if (err) {
          console.log(err);
          return reject(err);
        }
        resolve(response);
      });
    });
    let data = res;
    if (typeof data === 'string') {
      data = {
        bodyString: data
      };
    }
    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.url = url;
    return data;
  } catch (err) {
    console.error(err);
    let data = err;
    if (typeof data === 'string') {
      data = {
        bodyString: data
      };
    }
    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.url = url;
    throw data;
  }
};
exports.fetch = fetch;
const getCookies = domain => {
  if (domain) {
    return WebSocketSslPinning.getCookies(domain);
  }
  return Promise.reject('Domain cannot be empty');
};
exports.getCookies = getCookies;
const removeCookieByName = name => {
  if (name) {
    return WebSocketSslPinning.removeCookieByName(name);
  }
  return Promise.reject('Cookie Name cannot be empty');
};
exports.removeCookieByName = removeCookieByName;
const closeWebSocket = () => {
  WebSocketSslPinning.closeWebSocket('Client initiated closure', (error, successMessage) => {
    if (error) {
      console.error('Error closing WebSocket:', error);
    } else {
      console.log(successMessage); // This will log "WebSocket successfully closed"
    }
  });
};
exports.closeWebSocket = closeWebSocket;
const sendWebSocketMessage = async message => {
  try {
    const res = await new Promise((resolve, reject) => {
      WebSocketSslPinning.sendWebSocketMessage(message, (err, response) => {
        if (err) {
          console.error('Error in WebSocket message sending:', err);
          return reject(err);
        }
        resolve(response);
      });
    });
    let data = res;
    if (typeof data === 'string') {
      data = {
        bodyString: data
      };
    }
    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.message = message;
    return data;
  } catch (err) {
    console.error(err);
    let data = err;
    if (typeof data === 'string') {
      data = {
        bodyString: data
      };
    }
    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.message = message;
    throw data;
  }
};
exports.sendWebSocketMessage = sendWebSocketMessage;
const eventEmitter = exports.eventEmitter = new _reactNative.NativeEventEmitter(_reactNative.NativeModules.WebSocketSslPinning);
//# sourceMappingURL=index.js.map