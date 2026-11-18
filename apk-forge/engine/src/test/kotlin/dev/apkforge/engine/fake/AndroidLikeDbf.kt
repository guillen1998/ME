package dev.apkforge.engine.fake

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * Imita el DocumentBuilderFactory de Android: rechaza las "features" de Xerces que Android no
 * conoce. Con él, el parser original de jadx revienta igual que en el móvil.
 */
class AndroidLikeDbf : DocumentBuilderFactory() {
    private val real: DocumentBuilderFactory = DocumentBuilderFactory.newDefaultInstance()
    private val supported = setOf(
        "http://xml.org/sax/features/namespaces",
        "http://xml.org/sax/features/validation",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
    )
    override fun setFeature(name: String, value: Boolean) {
        if (name !in supported) throw ParserConfigurationException("Unsupported feature: $name")
        real.setFeature(name, value)
    }
    override fun getFeature(name: String) = real.getFeature(name)
    override fun newDocumentBuilder(): DocumentBuilder = real.newDocumentBuilder()
    override fun setAttribute(name: String, value: Any?) = real.setAttribute(name, value)
    override fun getAttribute(name: String): Any? = real.getAttribute(name)
    override fun setXIncludeAware(state: Boolean) {
        if (state) throw UnsupportedOperationException()
    }
}
