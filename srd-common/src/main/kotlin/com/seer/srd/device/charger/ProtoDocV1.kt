package com.seer.srd.device.charger
/*
【数据样例】
[
  {
    "name": "charger1",
    "ip": "127.0.0.1",
    "port": 9000,
    "enabled": true,       // true: 启用； false: 禁用
    "online": true,       // true: 在线， false: 离线
    "status": -1          // -1：故障;  0：空闲;  1:充电中
    "voltage": "20"       // 电压值 （V）
    "current": "4"         // 电流 （A）
  }
]

【协议样例】
  【报文1：BMS --> CCS】- 发数据给充电机
    demo: 88 18 06 e5 f4 01 20 00 fa 00 00 00 00
    前5个字节为常量 - 88 18 06 e5 f4
    后8个字节为要传输的数据(data_send) - 01 20 00 fa 00 00 00 00
      data_send[0]:  最高允许充电端电压高字节
      data_send[1]:  最高允许充电端电压低字节 - 0.1V/bit 偏移量： 0 例： Vset =3201，对应电压为 320.1v
      data_send[2]:  最高允许充电电流高字节
      data_send[3]:  最高允许充电电流低字节   - 0.1A/bit 偏移量： 0 例： Iset =582，对应电流为 58.2A
      data_send[4]:  控制                   - 0：充电机开启充电。 1：电池保护，充电器关闭输出。
      data_send[5]:  保留
      data_send[6]:  保留
      data_send[7]:  保留

  【报文2：CCS --> BCA】- 接收充电机的数据
    demo: 88 18 ff 50 e5 01 0f 00 f7 08 00 00 00
    前5个字节为常量 - 88 18 ff 50 e5
    后8个字节为要传输的数据(data_recv) - 01 0f 00 f7 08 00 00 00
      data_recv[0]:  输出电压高字节
      data_recv[1]:  输出电压低字节 -  0.1V/bit 偏移量： 0 例： Vout =3201，对应电压为 320.1v
      data_recv[2]:  输出电流高字节
      data_recv[3]:  输出电流低字节 -  0.1A/bit 偏移量： 0 例： Iout =582，对应电流为 58.2A
      data_recv[4]:  状态标志STATUS
        STATUS[0]:  硬件故障 -   0：正常。 1：硬件故障
        STATUS[1]:  充电机温度 - 0：正常。 1：充电机温度过高保护
        STATUS[2]:  输入电压 -   0：输入电压正常。 1：输入电压错误，充电机停止工作
        STATUS[3]:  启动状态 -   0：充电器检测到电池电压进入启动状态。 1：处于关闭状态。（用于防止电池反接）
        STATUS[4]:  通信状态 -   0：通信正常。 1：通信接收超时
        STATUS[5]:  无
        STATUS[6]:  无
        STATUS[7]:  无
      data_recv[5]:  保留
      data_recv[6]:  保留
      data_recv[7]:  保留

【工作方式】
  1. BMS 固定间隔时间 1S 发送控制信息（报文 1）到充电机，充电机接收到信息以后根据报文数据的电压电流设置来工作。
  如果 5 秒接收不到报文，则进入通信错误状态，关闭输出。
  2. 充电机每隔 1S 发送广播信息（报文 2），显示仪表可以根据信息显示充电机状态。


【充电过程】
1、get - /locationDevices/CP2?action = enter
接收到指令之后:
 ① 获取调度中的机器人列表。
 ② 遍历列表中每个机器人的终点，并通过终点是否为`CP2`找到目标车辆。
 ③ 获取目标车辆的`requestCurrent`和`requestVoltage`，并进行校验。
 ④ 每秒给充电机发送一次充电指令。
 ⑤ 当检测到充电机充电成功之后，向请求方回复`{actionStatus: DONE}`

2、get - /locationDevices/CP2?action = leave
接收到指令之后：
 ① 根据内存中当前的`maxVoltage`和`maxCurrent`给充电机发送一次取消充电的指令。
 ② 当检测到充电机取消充电成功之后，向请求方回复`{actionStatus: DONE}`


【注意事项】
  1、电流和电业有可能是负数。

*/