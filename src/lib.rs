//! Network policy evaluated only when configuration changes, never per packet.

/// Bit 0 selects Wi-Fi restrictions; bit 1 selects mobile restrictions.
/// Unclassified connections conservatively apply both sets of restrictions.
pub fn block_mask(network: i32) -> i32 {
    match network {
        1 => 1,
        2 => 2,
        _ => 3,
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_ntroll_app_NativeFirewall_blockMask(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    network: jni::sys::jint,
) -> jni::sys::jint {
    block_mask(network)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn independent_wifi_and_mobile_rules() {
        for wifi in [false, true] {
            for mobile in [false, true] {
                let rule = i32::from(wifi) | (i32::from(mobile) << 1);
                assert_eq!(rule & block_mask(1) != 0, wifi);
                assert_eq!(rule & block_mask(2) != 0, mobile);
                for unknown in [-1, 0, 3, i32::MAX] {
                    assert_eq!(rule & block_mask(unknown) != 0, wifi || mobile);
                }
            }
        }
    }
}
