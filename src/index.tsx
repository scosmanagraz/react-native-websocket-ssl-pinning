import { NativeModules, Platform, NativeEventEmitter } from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-websocket-ssl-pinning' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const WebSocketSslPinning = NativeModules.WebSocketSslPinning
  ? NativeModules.WebSocketSslPinning
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

const fetch = async (url: String, obj: any) => {
  if (obj.headers) {
    obj.headers = Object.keys(obj.headers).reduce(
      (acc, key) => ({
        ...acc,
        [obj.caseSensitiveHeaders ? key : key.toLowerCase()]: obj.headers[key],
      }),
      {}
    );
  }

  try {
    const res = await new Promise<any>((resolve, reject) => {
      WebSocketSslPinning.fetch(url, obj, (err: String, response: String) => {
        if (err) {
          console.log(err);
          return reject(err);
        }
        resolve(response);
      });
    });

    let data = res;

    if (typeof data === 'string') {
      data = { bodyString: data };
    }

    data.json = async () =>
      JSON.parse((data as { bodyString: string }).bodyString);
    data.text = async () => data.bodyString;
    data.url = url;

    return data;
  } catch (err: any) {
    console.error(err);
    let data = err;

    if (typeof data === 'string') {
      data = { bodyString: data };
    }

    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.url = url;

    throw data;
  }
};

const getCookies = (domain: string) => {
  if (domain) {
    return WebSocketSslPinning.getCookies(domain);
  }

  return Promise.reject('Domain cannot be empty');
};

const removeCookieByName = (name: string) => {
  if (name) {
    return WebSocketSslPinning.removeCookieByName(name);
  }

  return Promise.reject('Cookie Name cannot be empty');
};

const closeWebSocket = () => {
  WebSocketSslPinning.closeWebSocket(
    'Client initiated closure',
    (error: string, successMessage: string) => {
      if (error) {
        console.error('Error closing WebSocket:', error);
      } else {
        console.log(successMessage); // This will log "WebSocket successfully closed"
      }
    }
  );
};

const sendWebSocketMessage = async (message: any) => {
  try {
    const res = await new Promise<any>((resolve, reject) => {
      WebSocketSslPinning.sendWebSocketMessage(
        message,
        (err: string, response: string) => {
          if (err) {
            console.error('Error in WebSocket message sending:', err);
            return reject(err);
          }
          resolve(response);
        }
      );
    });

    let data = res;

    if (typeof data === 'string') {
      data = { bodyString: data };
    }

    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.message = message;

    return data;
  } catch (err: any) {
    console.error(err);
    let data = err;

    if (typeof data === 'string') {
      data = { bodyString: data };
    }

    data.json = async () => JSON.parse(data.bodyString);
    data.text = async () => data.bodyString;
    data.message = message;

    throw data;
  }
};

const eventEmitter = new NativeEventEmitter(NativeModules.WebSocketSslPinning);

export {
  fetch,
  getCookies,
  removeCookieByName,
  sendWebSocketMessage,
  eventEmitter,
  closeWebSocket,
};
