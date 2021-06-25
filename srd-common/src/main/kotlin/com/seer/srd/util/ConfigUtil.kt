package com.seer.srd.util

import com.seer.srd.util.CfgHelper.enableCfg
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.representer.Representer
import java.nio.charset.StandardCharsets
import kotlin.reflect.KClass

private val LOG = LoggerFactory.getLogger("com.seer.srd")

fun <TConfig : Any> loadConfig(configFilename: String, configClass: KClass<TConfig>): TConfig? {
    enableCfg()
    val configFile = searchFileFromWorkDirUp(configFilename)
    if (configFile != null) {
        val rp = Representer()
        rp.propertyUtils.isSkipMissingProperties = true
        val yaml = Yaml(Constructor(configClass.java), rp)
        val configStr = FileUtils.readFileToString(configFile, StandardCharsets.UTF_8)
        val config = yaml.load<TConfig>(configStr)
        LOG.info("Config Loaded from $configFile")
        LOG.info(config.toString());
        return config
    }
    return null
}