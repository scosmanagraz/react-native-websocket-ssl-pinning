@objc(WebSocketSslPinning)
class WebSocketSslPinning: NSObject {

// TODO iOS implementation
  @objc(multiply:withB:withResolver:withRejecter:)
  func multiply(a: Float, b: Float, resolve:RCTPromiseResolveBlock,reject:RCTPromiseRejectBlock) -> Void {
    resolve(a*b)
  }
}
