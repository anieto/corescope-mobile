# Localization terminology

MeshCore vocabulary should read the same in NodeScope as in the MeshCore apps people already use.
Before translating a new language, start from the reference table below, then record the choices
in "NodeScope terms".

## NodeScope terms

Spanish (`es`): neutral Latin American Spanish, informal *tú*. Brazilian Portuguese (`pt-BR`): informal
*você*, following meshcore-open's `pt`. Both use sentence case like the English. Compass points follow
each language: west is O in both, and east is L in pt-BR.

| English | es | pt-BR | Note |
|---|---|---|---|
| node | nodo | nó | |
| repeater | repetidor | repetidor | |
| companion | acompañante | companheiro | es as MeshCore One; pt-BR as meshcore-open |
| room server | servidor de sala | servidor de sala | role label "Room" is "Sala" in both |
| sensor | sensor | sensor | |
| observer | observador | observador | |
| advert | anuncio | anúncio | the CLI command stays `advert` |
| flood | flood | flood | kept in English: "anuncio flood", "anúncio flood" |
| zero-hop | zero-hop | zero-hop | kept in English |
| hop | salto | salto | |
| path / route | ruta | rota | |
| channel | canal | canal | |
| region | región | região | |
| analyzer | analizador | analisador | |
| packet | paquete | pacote | |
| mesh | red mesh | rede mesh | |
| heard (by) | escuchado (por) | ouvido (por) | |
| last heard | última vez escuchado | ouvido pela última vez | |
| signal | señal | sinal | |
| noise floor | ruido de fondo | nível de ruído | es as MeshCore One; pt-BR as meshcore-open |
| link margin | margen del enlace | margem do enlace | |
| line of sight | línea de vista | linha de visada | |
| Fresnel zone | zona de Fresnel | zona de Fresnel | |
| Settings | Ajustes | Ajustes | Apple's term in both; shorter than Configuración / Configurações in the tab bar |
| Live Map / Explore | Mapa en vivo / Explorar | Mapa ao vivo / Explorar | |
| My Nodes / Favorites | Mis nodos / Favoritos | Meus nós / Favoritos | |
| Node Planner | Planificador de nodos | Planejador de nós | |
| Link Profile | Perfil de enlace | Perfil de enlace | |
| Estimated Coverage | Cobertura estimada | Cobertura estimada | |
| Saved Plans / Candidate Sites | Planes guardados / Sitios candidatos | Planos salvos / Locais candidatos | |
| Field Tools | Herramientas de campo | Ferramentas de campo | |
| Node Check | Revisión de nodo | Verificação de nó | |
| What's New / Glossary | Novedades / Glosario | Novidades / Glossário | |
| NodeScope, CoreScope, MeshCore, Beacon | unchanged | unchanged | product names |
| SNR, RSSI, dB, dBm, dBi, MHz | unchanged | unchanged | |

## Reference: other MeshCore apps

Labels as shown in two open-source MeshCore clients, written "MeshCore One / meshcore-open"; "–" means
that app has no matching label. Taken from [MeshCore One](https://github.com/Avi0n/MeshCoreOne) at
`5e980a5` (GPL-3.0) and [meshcore-open](https://github.com/zjs81/meshcore-open) at `34ea0cb` (MIT) on
2026-11-17.

MeshCore One's `pt` is European Portuguese (Definições, Contactos). meshcore-open's `pt` is
Brazilian (Configurações, Contatos) and it ships `pt_PT` separately, so use meshcore-open for pt-BR.

| English | es | pt | de | pl | fr | it | nl |
|---|---|---|---|---|---|---|---|
| node | Nodo / – | Nó / – | Knoten / – | Węzeł / – | Nœud / – | Nodo / – | Knooppunt / – |
| repeater | Repetidor | Repetidor | Repeater | Przekaźnik | Répéteur | Ripetitore | Repeater |
| companion | Acompañante / – | Contacto / – | Companion / – | Towarzysz / – | Compagnon / – | Companion / – | Companion / – |
| room server | Servidor de sala | Servidor de sala | Raumserver | Serwer pokoju | Serveur de salle | Server stanza | Ruimteserver / Roomserver |
| sensor | Sensor | Sensor | Sensor | Czujnik | Capteur | Sensore | Sensor |
| advert | Anuncio / – | Advert / – | Ankündigung / – | Ogłoszenie / – | Annonce / – | Annuncio / – | Advertentie / – |
| flood | Flood | Difusão / Flood | Flood | Flood | Flood | Flood | Flood |
| zero-hop | Zero-Hop / – | Zero-hop / – | Zero-Hop / – | Zero-Hop / – | Zero-Hop / – | Zero-hop / – | Zero-Hop / – |
| hops | Saltos / – | Saltos / – | Hops / – | Skoki / – | Sauts / – | Salti / – | Sprongen / – |
| path | Ruta | Rota / Caminho | Pfad | Ścieżka | Chemin | Percorso | Pad |
| route | Ruta / – | Rota / – | Route / – | Trasa / – | Itinéraire / – | Rotta / – | Route / – |
| channels | Canales | Canais | Kanäle | Kanały | Canaux | Canali | Kanalen |
| contacts | Contactos | Contactos / Contatos | Kontakte | Kontakty | Contacts | Contatti | Contacten |
| settings | Ajustes / Configuración | Definições / Configurações | Einstellungen | Ustawienia | Réglages / Paramètres | Impostazioni | Instellingen |
| region | Región / – | Região / – | Region / – | Region / – | Région / – | Region / – | Regio / – |
| noise floor | Ruido de fondo / Nivel de ruido | Ruído de fundo / Nível de ruído | Grundrauschen / Rauschpegel | Próg szumów / Poziom szumów | Plancher de bruit / Niveau de bruit | Rumore di fondo | Ruisvloer |
| signal | – / Señal | – / Sinal | – / Signal | – / Sygnał | – / Signal | – / Segnale | – / Signaal |
| trace path | Trazar ruta / – | Traçar rota / – | Pfad verfolgen / – | Śledzenie ścieżki / – | Tracer le chemin / – | Traccia percorso / – | Pad traceren / – |
| public key | Clave pública | Chave pública | Öffentlicher Schlüssel | Klucz publiczny | Clé publique | Chiave pubblica | Publieke / Openbare sleutel |
| last heard | Última escucha / – | Última receção / – | Zuletzt gehört / – | Ostatnio usłyszany / – | Dernière écoute / – | Ultimo ascolto / – | Laatst gehoord / – |
| telemetry | Telemetría | Telemetria | Telemetrie | Telemetria / Telemetryka | Télémétrie | Telemetria | Telemetrie |
| neighbors | Vecinos | Vizinhos | Nachbarn | Sąsiedzi | Voisins | Vicini | Buren |
| radio settings | Ajustes de radio | Definições do rádio / Configurações de rádio | Funkeinstellungen | Ustawienia radia | Paramètres radio | Impostazioni radio | Radio-instellingen |

To refresh this table, compare `lib/l10n/app_<lang>.arb` (meshcore-open) and
`MC1/Resources/Localization/<lang>.lproj/*.strings` (MeshCore One) against their English files.
