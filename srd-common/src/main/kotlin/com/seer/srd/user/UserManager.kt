package com.seer.srd.user

import com.seer.srd.CONFIG
import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.PagingResult
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.lang3.RandomStringUtils
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

object UserManager {

    private val logger = LoggerFactory.getLogger(UserManager::class.java)

    fun hashPassword(password: String): String {
        return if (password.isBlank()) "" else DigestUtils.md5Hex(password + password)
    }

    @Volatile
    var passwordMatcher: (target: String, notSalted: String) -> Boolean = { target, notSalted ->
        hashPassword(notSalted) == target
    }

    fun prepareDefaultAdmin() {//定义登录的账户与密码
        //集合c中存储登录的账户与密码
        val c = collection<HumanUser>()
        val existedAdmin = c.findOne(HumanUser::admin eq true)
        if (existedAdmin != null) return
        logger.info("No default admin user, create one")
        val admin = HumanUser(ObjectId(), "admin", hashPassword("SeerSRD_Admin123"), "", admin = true)
        c.insertOne(admin)
    }

    fun validateUserSession(userId: ObjectId, userToken: String): UserWithRolesPermissions? {
        val c = collection<UserSession>()
        val session = c.findOne(UserSession::userId eq userId, UserSession::userToken eq userToken) ?: return null
        if (session.expiredAt < System.currentTimeMillis()) return null
        val user = getUserById(userId) ?: return null
        return getUserWithRolesPermissions(user)
    }

    fun signIn(username: String, password: String): UserSession {
        if (password.isBlank()) throw Error400("PasswordNotMatch", "密码错误")

        val c = collection<HumanUser>()
        val user = c.findOne(HumanUser::username eq username)
            ?: throw Error400("UserNotExisted", "用户不存在")
        if (user.disabled) throw Error400("UserDisabled", "账户已被禁用")
        if (!passwordMatcher(user.password, password)) throw Error400("PasswordNotMatch", "密码错误")
        return signInSuccessfully(user)
    }

    private fun signInSuccessfully(user: HumanUser): UserSession {
        signOut(user.id) // 先退出

        val sessionExpireMinutes = CONFIG.sessionExpireMinutes
        val expireAt = System.currentTimeMillis() + sessionExpireMinutes * 1000 * 60
        logger.debug("new session $sessionExpireMinutes,$expireAt")
        val token = RandomStringUtils.randomAlphanumeric(24)
        val session = UserSession(ObjectId(), user.id, token, expireAt)

        collection<UserSession>().insertOne(session)

        return session
    }

    fun signOut(userId: ObjectId) {
        collection<UserSession>().deleteMany(UserSession::userId eq userId)
    }

    fun listUser(pageNo: Int, pageSize: Int): PagingResult<HumanUser> {
        val c = collection<HumanUser>()
        val total = c.countDocuments()
        val page = c.find().limit(pageSize).skip((pageNo - 1) * pageSize).toList()
        return PagingResult(total, page, pageNo, pageSize)
    }

    fun getUserForEditById(id: ObjectId): HumanUser? {
        val user = getUserById(id) ?: return null
        return user.copy(password = "")
    }

    fun createUser(user: HumanUser): ObjectId {
        collection<HumanUser>().insertOne(user)
        return user.id
    }

    fun updateUser(id: ObjectId, user: HumanUser) {
        val c = collection<HumanUser>()
        val old = c.findOne(HumanUser::id eq id) ?: throw Error400("UpdateNoUser", "UpdateNoUser")
        var newUser = old.copy(
            username = user.username, nickname = user.nickname, disabled = user.disabled, rolesIds = user.rolesIds
        )
        if (user.password.isNotBlank()) newUser = newUser.copy(password = user.password)
        c.replaceOne(HumanUser::id eq id, newUser)
    }

    fun listRoles(pageNo: Int, pageSize: Int): PagingResult<UserRole> {
        val c = collection<UserRole>()
        val total = c.countDocuments()
        val page = c.find().limit(pageSize).skip((pageNo - 1) * pageSize).toList()
        return PagingResult(total, page, pageNo, pageSize)
    }

    fun getAllRoles(): List<UserRole> {
        return collection<UserRole>().find().toList()
    }

    fun getUserRoleById(id: ObjectId): UserRole? {
        return collection<UserRole>().findOne(UserRole::id eq id)
    }

    fun createRole(role: UserRole): ObjectId {
        collection<UserRole>().insertOne(role)
        return role.id
    }

    fun updateRole(role: UserRole) {
        val c = collection<UserRole>()
        val old = c.findOne(UserRole::id eq role.id) ?: throw Error400("UpdateNoRole", "UpdateNoRole")
        val newRole = old.copy(name = role.name, permissions = role.permissions)
        c.replaceOne(UserRole::id eq role.id, newRole)
    }

    fun getUserById(id: ObjectId): HumanUser? {
        return collection<HumanUser>().findOne(HumanUser::id eq id)
    }

    private fun getUserWithRolesPermissions(user: HumanUser): UserWithRolesPermissions {
        if (user.rolesIds.isEmpty()) return UserWithRolesPermissions(user, emptyList(), emptySet())
        val roles = collection<UserRole>().find(UserRole::id `in` user.rolesIds).toList()
        val permissions: MutableSet<String> = HashSet()
        for (role in roles) permissions.addAll(role.permissions)
        return UserWithRolesPermissions(user, roles, permissions)
    }

}

data class UserSession(
    @BsonId val id: ObjectId = ObjectId(),
    val userId: ObjectId = ObjectId(),
    val userToken: String = "",
    val expiredAt: Long = 0
)

//数据类保存用户信息，用户名密码等
data class HumanUser(
    @BsonId val id: ObjectId = ObjectId(),
    val username: String = "",
    val password: String = "",
    val nickname: String = "",
    val admin: Boolean = false,
    val disabled: Boolean = false,
    val rolesIds: List<ObjectId> = emptyList(),
    val createdOn: Instant = Instant.now()
)

data class UserRole(
    @BsonId val id: ObjectId = ObjectId(),
    val name: String = "",
    val permissions: List<String> = emptyList()
)

class UserWithRolesPermissions(
    val user: HumanUser,
    val roles: List<UserRole>,
    val permissions: Set<String>
)