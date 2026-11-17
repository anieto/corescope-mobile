# Localization terminology

MeshCore vocabulary should read the same in NodeScope as in the MeshCore apps people already use.
Before translating a new language, start from the reference table below, then record the choices
in "NodeScope terms".

## NodeScope terms

Spanish (`es`) — neutral Latin American Spanish, informal *tú*, sentence case like the English.

| English | es | Note |
|---|---|---|
| node | nodo | |
| repeater | repetidor | |
| companion | acompañante | as MeshCore One |
| room server | servidor de sala | role label "Room" is "Sala" |
| sensor | sensor | |
| observer | observador | |
| advert | anuncio | the CLI command stays `advert` |
| flood | flood | kept in English: "anuncio flood", "ruta flood" |
| zero-hop | zero-hop | kept in English |
| hop | salto | |
| path / route | ruta | |
| channel | canal | |
| region | región | |
| analyzer | analizador | |
| packet | paquete | |
| mesh | red mesh | |
| heard (by) | escuchado (por) | |
| last heard | última vez escuchado | |
| signal | señal | |
| noise floor | ruido de fondo | as MeshCore One |
| link margin | margen del enlace | |
| line of sight | línea de vista | |
| Fresnel zone | zona de Fresnel | |
| Settings | Ajustes | |
| Live Map / Explore | Mapa en vivo / Explorar | |
| My Nodes / Favorites | Mis nodos / Favoritos | |
| Node Planner | Planificador de nodos | |
| Link Profile | Perfil de enlace | |
| Estimated Coverage | Cobertura estimada | |
| Saved Plans / Candidate Sites | Planes guardados / Sitios candidatos | |
| Field Tools | Herramientas de campo | |
| Node Check | Revisión de nodo | |
| What's New / Glossary | Novedades / Glosario | |
| NodeScope, CoreScope, MeshCore, Beacon | unchanged | product names |
| SNR, RSSI, dB, dBm, dBi, MHz | unchanged | |

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
