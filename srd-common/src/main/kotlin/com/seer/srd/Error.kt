package com.seer.srd

import kotlin.RuntimeException

open class BaseError(message: String?, cause: Throwable? = null) : RuntimeException(message, cause)

open class SystemError(message: String?, cause: Throwable? = null) : BaseError(message, cause)

open class BusinessError(message: String?, cause: Throwable? = null) : BaseError(message, cause)

open class Error401(message: String? = null) : BaseError(message)

open class Error403(message: String? = null) : BaseError(message)

open class Error400(val code: String, message: String?) : BaseError(message)

open class Error404(message: String? = null) : BaseError(message)

class SyncRouteOrderError(message: String?) : BusinessError(message)

class SkipCurrentTransport(message: String) : BusinessError(message)

class TaskAbortedError(message: String) : BusinessError(message)

class RouteOrderFailedError(message: String = "") : BusinessError(message)

class NoSuchStoreSite(id: String) : Error400("NoSuchStoreSite", id) // todo lang in wrap

class FailedLockStoreSite(siteId: String) : Error400("FailedLockStoreSite", siteId) // todo lang in wrap
class FailedUnlockStoreSite(siteId: String) : Error400("FailedUnlockStoreSite", siteId) // todo lang in wrap

class NoEmptyStoreSite(type: String) : Error400("NoEmptyStoreSite", type) // todo lang in wrap

class FailedChangedStoreSiteState(siteId: String) : Error400("FailedChangedStoreSiteState", siteId) // todo lang in wrap

class NoMatchSite(reason: String) : Error400("NoMatchSite", reason) // todo lang in wrap

class SendOrderToRouteError(message: String) : BusinessError(message)

class RetryMaxError(message: String) : BusinessError(message)

class ComponentDefNotFound(name: String) : BusinessError("ComponentDefNotFound $name")

class NoTaskComponentParamDef(name: String) : BusinessError("ComponentDefNotFound $name")

class WithMemoryLockFailed : BusinessError("WithMemoryLockFailed")

class RbkNotConnected : BaseError("")