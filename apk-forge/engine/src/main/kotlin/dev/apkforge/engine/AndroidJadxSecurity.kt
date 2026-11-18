package dev.apkforge.engine

import jadx.api.security.JadxSecurityFlag
import jadx.api.security.impl.JadxSecurity
import org.w3c.dom.Document
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * jadx crea su parser XML activando 5 "features" de Xerces de golpe; el parser de Android
 * (KXmlParser/Harmony) rechaza varias y la clase interna SecureDBFHolder revienta al iniciarse
 * ("No se pudo cargar para Java: …SecureDBFHolder"). Aquí activamos cada protección por separado
 * e ignoramos solo las que la plataforma no soporta: sigue sin expandir entidades externas.
 */
internal class AndroidJadxSecurity : JadxSecurity(
    JadxSecurityFlag.all().apply { remove(JadxSecurityFlag.SECURE_XML_PARSER) }
) {
    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            fun feature(name: String, value: Boolean) = runCatching { setFeature(name, value) }
            feature("http://apache.org/xml/features/disallow-doctype-decl", true)
            feature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            feature("http://xml.org/sax/features/external-general-entities", false)
            feature("http://xml.org/sax/features/external-parameter-entities", false)
            feature("http://apache.org/xml/features/dom/create-entity-ref-nodes", false)
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            isNamespaceAware = false
        }
    }

    override fun parseXml(input: InputStream): Document =
        synchronized(factory) { factory.newDocumentBuilder() }.parse(input)
}
