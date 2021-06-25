package com.seer.srd.device.charger
/*
 * CANWIFI-200T
 *
 * 【工作端口数据转换格式】
 *    - header:                       2 byte
 *                                      [0xFE, 0XFD]
 *    - mode:                         1 byte
 *                                      0x00 - 正常发送； 0x01 - 自发自收
 *    - summary:                      1 byte
 *        - summary[7]:               FF - 标准帧和扩展帧的标识。
 *                                      1 - 扩展帧; 0 - 标准帧
 *        - summary[6]:               RTR - 远程帧和数据帧的标识。
 *                                      1 - 远程帧; 0 - 数据帧
 *        - summary[5]:               保留位 - 0
 *        - summary[4]:               保留位 - 0
 *        - summary[3] ~ summary[0]:  数据长度；例如 0100 表示此CAN帧为4字节数据段
 *    - id:                           4 byte
 *        - 标准帧(0x3FF)             - 高[0x00, 0x00, 0x03, 0xff]低
 *        - 扩展帧(0X12345678)        - 高[0x12, 0x34, 0x56, 0x78]低
 *    - data:                         8 byte。其长度由 summary[3]~summary[0]决定
 *    - timeStamp：                   3 byte。ms。从上电开始计时，溢出后归零，继续计时。
 *    - check:                        1 byte。 byte0^byte1^...^byte17^byte18
 *
 * 【数据样例】
 *    - 【发数据给充电机】
 *        demo: FE FD 00 88 18 06 E5 F4 00 00 00 00 02 00 00 00 00 05 B7 37
 *          - header(2):     FE FD          // 固定值
 *          - mode(1):       00             // 固定值
 *          - summary(1):    88             // 固定值 1000 1000 扩展帧，数据段的长度为8
 *          - id(4):         18 06 E5 F4    // 固定值
 *          - data(8):       00 00 00 00 02 00 00 00 // 参考第一版协议的数据区格式
 *          - timeStamp(3):  00 05 B7       // 如何计算？？？？？？？
 *          - check(1):      37             // 之前19个字节的异或值
 *
 *        【to do】
 *          - 设置 data 的值。
 *          - 设置 timeStamp。
 *          - 计算校验和。
 *
 *    - 【接收充电机的数据】
 *        demo: FE FD 00 88 18 FF 50 E5 00 00 00 00 10 01 00 00 2F 22 01 C4
 *          - header(2):     FE FD          // 固定值
 *          - mode(1):       00             // 固定值
 *          - summary(1):    88             // 固定值 1000 1000 扩展帧，数据段的长度为8
 *          - id(4):         18 FF 50 E5    // 固定值
 *          - data(8):       00 00 00 00 10 01 00 00 // 参考第一版协议的数据区格式
 *          - timeStamp(3):  2F 22 01       // 如何计算？？？？？？？
 *          - check(1):      C4             // 之前19个字节的异或值
 *
 *        【to do】
 *          - 解析 data 的值。
 *          - 计算校验和。
 *
 *
 * 【工作方式】
 *    建议用户每路 CAN 每秒发送的 CAN 帧不要超过 5800 帧
 *
 *
 * 【CAN 口状态的 TCP 通知端口数据转换格式】
 *    - header:                       2 byte
 *                                      [0xAA, 0x00]
 *    - cmd:                          1 byte
 *                                      0x00 ~ 0x0C
 *    - data:                         4 byte
 *    - end:                          1 byte
 *                                      0x55
 */
