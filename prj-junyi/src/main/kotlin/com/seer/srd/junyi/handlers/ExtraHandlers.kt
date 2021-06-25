package com.seer.srd.junyi.handlers

import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta

fun registerDefaultHttpHandlers() {

    val operator = Handlers("task-by-operator")
    // 切换岗位和工位时，用于校验“确认密码”   PAD -> SRD-K
    operator.post("check-password", ExtHandlers::checkPassword, ReqMeta(test = true, auth = false))
    // P010 - 放行     PAD -> SRD-K
    operator.post("p010-finished", ExtHandlers::p010Finished, ReqMeta(test = true, auth = false))
    // P020 - 放行     PAD -> SRD-K
    operator.post("p020-finished", ExtHandlers::p020Finished, ReqMeta(test = true, auth = false))
    // P030 - 放行     PDA -> SRD-K -> MES
    operator.post("p030-finished", ExtHandlers::p030Finished, ReqMeta(test = true, auth = false))
    // 模组固定 - 放行     PAD -> SRD-K
    operator.post("fix-module-finished", ExtHandlers::fixModuleFinished, ReqMeta(test = true, auth = false))
    // 高压铜线安装 - 放行 PAD -> SRD-K
    operator.post("load-high-vol-cable-finished", ExtHandlers::loadHighVolCableFinished, ReqMeta(test = true, auth = false))
    // 水冷板检测 - 放行   PAD -> SRD-K
    operator.post("board-test-finished", ExtHandlers::boardTestFinished, ReqMeta(test = true, auth = false))
    // 线束安装 - 放行   PAD -> SRD-K
    operator.post("load-wire-finished", ExtHandlers::loadWireFinished, ReqMeta(test = true, auth = false))
    // 气密性检测 - 放行   PAD -> SRD-K
    operator.post("air-tightness-test-finished", ExtHandlers::airTightnessTestFinished, ReqMeta(test = true, auth = false))
    // P090 - 放行     PAD -> SRD-K
    operator.post("p090-finished", ExtHandlers::p090Finished, ReqMeta(test = true, auth = false))
    // P100 - 放行     PAD -> SRD-K
    operator.post("p100-finished", ExtHandlers::p100Finished, ReqMeta(test = true, auth = false))
    // P130 - 放行     PAD -> SRD-K
    operator.post("p130-finished", ExtHandlers::p130Finished, ReqMeta(test = true, auth = false))

    // 上壳体安装 - 放行   PAD -> SRD-K -> MES
    operator.post("load-top-shell-finished", ExtHandlers::loadTopShellFinished, ReqMeta(test = true, auth = false))

    val ext = Handlers("ext")
    // 模组入壳 - 放行    MES -> SRD-K
    ext.post("load-module-finished", ExtHandlers::loadModuleFinished, ReqMeta(test = true, auth = false))
    // 螺丝紧固 - 放行    MES -> SRD-K
    ext.post("fix-screw-finished", ExtHandlers::fixScrewFinished, ReqMeta(test = true, auth = false))

    val mock = Handlers("ext/mock")
    mock.post("load-bottom-shell-finished", ExtHandlers::loadBottomShellFinishedInnerCall, ReqMeta(auth = false))
    mock.post("vehicle-at-load-module-pos", ExtHandlers::vehicleAtLoadModulePosInnerCall, ReqMeta(auth = false))
    mock.post("fix-module-already-called", ExtHandlers::fixModuleAlreadyCalledInnerCall, ReqMeta(auth = false))
    mock.post("vehicle-at-fix-module-pos", ExtHandlers::vehicleAtLoadModuleNextPosInnerCall, ReqMeta(auth = false))

    mock.post("load-top-shell-finished", ExtHandlers::loadTopShellFinishedInnerCall, ReqMeta(auth = false))
    mock.post("vehicle-at-fix-screw-pos", ExtHandlers::vehicleAtFixScrewPosInnerCall, ReqMeta(auth = false))
    mock.post("air-tightness-test-already-called", ExtHandlers::airTightnessTestAlreadyCalledInnerCall, ReqMeta(auth = false))
    mock.post("vehicle-at-air-tightness-test-pos", ExtHandlers::vehicleAtFixScrewNextPosInnerCall, ReqMeta(auth = false))

}