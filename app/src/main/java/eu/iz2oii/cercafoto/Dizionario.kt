package eu.iz2oii.cercafoto

import java.text.Normalizer

object Testo {
    private val segni = Regex("\\p{Mn}+")
    private val nonParola = Regex("[^a-z0-9]+")
    /** minuscolo, senza accenti, solo lettere/cifre separate da uno spazio */
    fun normalizza(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(segni, "").replace(nonParola, " ").trim()
}

/**
 * Traduzione in italiano delle ~440 etichette del modello ML Kit e categorie di ricerca.
 * Formato: Etichetta|termine1,termine2 (il primo termine è quello mostrato).
 */
object Dizionario {

    private const val ETICHETTE = """
Team|squadra,team
Bonfire|falò,fuoco
Comics|fumetti,fumetto
Himalayan|gatto himalayano,gatto
Iceberg|iceberg,ghiaccio
Bento|bento
Sink|lavandino,lavello
Toy|giocattolo,giocattoli
Statue|statua,statue,scultura
Cheeseburger|hamburger,panino
Tractor|trattore
Sled|slitta
Aquarium|acquario
Circus|circo
Sitting|seduti,seduto,seduta
Beard|barba
Bridge|ponte
Tights|collant
Bird|uccello,uccelli
Rafting|rafting
Park|parco
Factory|fabbrica,stabilimento
Graduation|laurea,diploma
Porcelain|porcellana
Twig|rametti
Petal|petali,fiore
Cushion|cuscino
Sunglasses|occhiali da sole,occhiali
Infrastructure|infrastruttura
Ferris wheel|ruota panoramica,luna park
Pomacentridae|pesce pagliaccio,pesce,pesci
Wetsuit|muta
Shetland sheepdog|cane,pastore
Brig|veliero,nave
Watercolor paint|acquerello,dipinto,quadro
Competition|gara,competizione
Cliff|scogliera,rupe
Badminton|badminton
Safari|safari
Bicycle|bicicletta,bici
Stadium|stadio
Boat|barca,barche
Smile|sorriso,sorridente
Surfboard|tavola da surf,surf
Fast food|fast food,panino
Sunset|tramonto,alba
Hot dog|hot dog
Shorts|pantaloncini
Bus|autobus,bus,pullman
Bullfighting|corrida
Sky|cielo
Gerbil|criceto,roditore
Rock|roccia,rocce,sasso,scoglio
Interaction|interazione
Dress|vestito,abito
Toe|dita dei piedi
Bear|orso
Eating|mangiare,mangia
Tower|torre
Brick|mattoni
Junk|rottami,spazzatura
Person|persona
Windsurfing|windsurf
Swimwear|costume da bagno,costume,bikini
Roller|rulli
Camping|campeggio,tenda
Playground|parco giochi,giostre
Bathroom|bagno
Laugh|risata,ridere
Balloon|palloncini,palloncino
Concert|concerto
Prom|ballo
Construction|cantiere,costruzione,lavori
Product|prodotto
Reef|barriera corallina,corallo
Picnic|picnic
Wreath|ghirlanda
Wheelbarrow|carriola
Boxer|boxer,pugile
Necklace|collana
Bracelet|braccialetto
Casino|casinò
Windshield|parabrezza
Stairs|scale,scala
Computer|computer,pc
Cookware and bakeware|pentole,teglia
Monochrome|bianco e nero
Chair|sedia,sedie
Poster|poster,manifesto
Bar|bar,bancone
Shipwreck|relitto
Pier|molo,pontile
Community|comunità
Caving|speleologia
Cave|grotta
Tie|cravatta
Cabinetry|mobili,armadio
Underwater|sott'acqua,subacqueo,fondale
Clown|pagliaccio,clown
Nightclub|discoteca
Cycling|ciclismo,bici
Comet|cometa
Mortarboard|tocco,laurea
Track|pista,binari
Christmas|natale,albero di natale
Church|chiesa
Clock|orologio
Dude|ragazzo
Cattle|mucche,mucca,bovini
Jungle|giungla
Desk|scrivania
Curling|curling
Cuisine|piatto,piatti,cucina
Cat|gatto,gatti,micio
Juice|succo
Couscous|cous cous
Screenshot|screenshot,schermata
Crew|equipaggio
Skyline|skyline,città
Stuffed toy|peluche
Cookie|biscotti,biscotto
Tile|piastrelle
Hanukkah|hanukkah
Crochet|uncinetto
Skateboarder|skater
Clipper|veliero
Nail|unghie,smalto
Cola|cola,bibita
Cutlery|posate
Menu|menu
Sari|sari
Plush|peluche
Pocket|tasca
Neon|neon,insegna
Icicle|ghiaccioli,ghiaccio
Pasteles|pasteles
Chain|catena
Dance|ballo,danza,ballare
Dune|dune,duna
Santa claus|babbo natale
Thanksgiving|ringraziamento
Tuxedo|smoking
Mouth|bocca
Desert|deserto
Dinosaur|dinosauro
Mufti|abiti civili
Fire|fuoco,fiamme,incendio
Bedroom|camera da letto,letto
Goggles|occhialini,maschera
Dragon|drago
Couch|divano
Sledding|slittino
Cap|cappellino,berretto
Whiteboard|lavagna
Hat|cappello
Gelato|gelato
Cavalier|cane
Beanie|berretto,cuffia
Jersey|maglia
Scarf|sciarpa
Vacation|vacanza,vacanze,ferie
Pitch|campo
Blackboard|lavagna
Deejay|dj
Monument|monumento
Bumper|paraurti
Longboard|longboard
Waterfowl|uccelli acquatici,anatre
Flesh|pelle
Net|rete
Icing|glassa,torta
Dalmatian|dalmata,cane
Speedboat|motoscafo
Trunk|tronco,bagagliaio
Coffee|caffè
Soccer|calcio,pallone
Ragdoll|gatto
Food|cibo
Standing|in piedi
Fiction|fantasia
Fruit|frutta
Pho|zuppa
Sparkler|stelline,scintille
Presentation|presentazione,slide
Swing|altalena
Cairn terrier|cane,terrier
Forest|bosco,foresta
Flag|bandiera
Frigate|fregata,nave
Foot|piede,piedi
Jacket|giacca,giubbotto
Pillow|cuscino
Bathing|bagno
Glacier|ghiacciaio
Gymnastics|ginnastica
Ear|orecchio
Flora|vegetazione,piante
Shell|conchiglie,conchiglia
Grandparent|nonni,nonno,nonna
Ruins|rovine,ruderi
Eyelash|ciglia
Bunk bed|letto a castello
Balance|equilibrio
Backpacking|escursione,zaino
Horse|cavallo,cavalli
Glitter|brillantini,glitter
Saucer|piattino
Hair|capelli
Miniature|miniatura,modellino
Crowd|folla,gente
Curtain|tende,tenda
Icon|icona
Pixie-bob|gatto
Herd|mandria,gregge
Insect|insetto,insetti
Ice|ghiaccio
Bangle|bracciale
Flap|aletta
Jewellery|gioielli,gioiello
Knitting|lavoro a maglia
Centrepiece|centrotavola
Outerwear|cappotto,giacca
Love|amore
Muscle|muscoli
Motorcycle|moto,motocicletta
Money|soldi,denaro,banconote
Mosque|moschea
Tableware|stoviglie,piatti
Ballroom|sala da ballo
Kayak|kayak,canoa
Leisure|tempo libero,svago
Receipt|scontrino,scontrini,ricevuta,fattura
Lake|lago
Lighthouse|faro
Bridle|briglia
Leather|cuoio,pelle
Horn|corno
Strap|cinghia
Lego|lego
Scuba diving|immersione,sub
Leggings|leggings
Pool|piscina
Musical instrument|strumento musicale,strumento
Musical|musical
Metal|metallo
Moon|luna
Blazer|giacca
Marriage|matrimonio,nozze
Mobile phone|telefono,cellulare,smartphone
Militia|militari
Tablecloth|tovaglia
Party|festa,party
Nebula|nebulosa
News|notizie
Newspaper|giornale
Piano|pianoforte,piano
Plant|pianta,piante
Passport|passaporto
Penguin|pinguino
Shikoku|cane
Palace|palazzo
Doily|centrino
Polo|polo
Paper|carta,foglio,fogli
Pop music|musica
Skiff|barca
Pizza|pizza
Pet|animale domestico
Quilting|trapunta
Cage|gabbia
Skateboard|skateboard
Surfing|surf
Rugby|rugby
Lipstick|rossetto,trucco
River|fiume
Race|gara,corsa
Rowing|canottaggio
Road|strada
Running|corsa,correre
Room|stanza,camera
Roof|tetto
Star|stella,stelle
Sports|sport
Shoe|scarpe,scarpa
Tubing|gommone
Space|spazio
Sleep|dorme,dormire,sonno
Skin|pelle
Swimming|nuoto,nuotare
School|scuola
Sushi|sushi
Loveseat|divanetto
Superman|superman
Cool|stile
Skiing|sci,sciare
Submarine|sottomarino
Song|canzone
Class|classe,lezione
Skyscraper|grattacielo
Volcano|vulcano
Television|televisione,tv
Rein|redini
Tattoo|tatuaggio,tatuaggi
Train|treno
Handrail|corrimano
Cup|tazza
Vehicle|veicolo,mezzo
Handbag|borsa,borsetta
Lampshade|lampada,paralume
Event|evento
Wine|vino
Wing|ala
Wheel|ruota
Wakeboarding|wakeboard
Web page|pagina web,sito
Ranch|ranch
Fishing|pesca,pescare
Heart|cuore
Cotton|cotone
Cappuccino|cappuccino
Bread|pane
Sand|sabbia
Museum|museo
Helicopter|elicottero
Mountain|montagna,monti
Duck|anatra,papera
Soil|terra,terreno
Turtle|tartaruga
Crocodile|coccodrillo
Musician|musicista
Sneakers|scarpe da ginnastica
Wool|lana
Ring|anello
Singer|cantante
Carnival|carnevale
Snowboarding|snowboard
Waterskiing|sci nautico
Wall|muro,parete
Rocket|razzo
Countertop|piano di lavoro
Beach|spiaggia
Rainbow|arcobaleno
Branch|ramo
Moustache|baffi
Garden|giardino,orto
Gown|abito lungo
Field|prato,campo
Dog|cane,cani
Superhero|supereroe
Flower|fiore,fiori
Placemat|tovaglietta
Subwoofer|cassa
Cathedral|cattedrale,duomo
Building|edificio,palazzo
Airplane|aereo
Fur|pelliccia,pelo
Bull|toro
Bench|panchina
Temple|tempio
Butterfly|farfalla
Model|modella,modello
Marathon|maratona
Needlework|ricamo,cucito
Kitchen|cucina
Castle|castello
Aurora|aurora boreale
Larva|bruco,larva
Racing|corsa,gara
Airliner|aereo di linea,aereo
Dam|diga
Textile|tessuto,stoffa
Groom|sposo
Fun|divertimento
Steaming|vapore
Vegetable|verdura,verdure,ortaggi
Unicycle|monociclo
Jeans|jeans
Flowerpot|vaso
Drawer|cassetto
Cake|torta,dolce
Armrest|bracciolo
Aviation|aviazione
Fog|nebbia
Fireworks|fuochi d'artificio,fuochi
Farm|fattoria
Seal|foca
Shelf|scaffale,mensola
Bangs|frangia
Lightning|fulmine,fulmini
Van|furgone
Sphynx|gatto
Tire|pneumatico,gomma
Denim|jeans
Prairie|prateria,prato
Snorkeling|snorkeling,maschera
Umbrella|ombrello,ombrellone
Asphalt|asfalto
Sailboat|barca a vela,vela
Basset hound|bassotto,cane
Pattern|motivo,fantasia
Supper|cena
Veil|velo
Waterfall|cascata
Lunch|pranzo
Odometer|cruscotto,contachilometri
Baby|bambino,neonato,bimbo,bebè
Glasses|occhiali
Car|auto,macchina,automobile
Aircraft|aereo,velivolo
Hand|mano,mani
Rodeo|rodeo
Canyon|canyon
Meal|pasto,piatto
Softball|softball
Alcohol|alcol,birra,drink
Bride|sposa
Swamp|palude
Pie|crostata,torta
Bag|borsa,zaino
Joker|joker
Supervillain|cattivo
Army|esercito,militari
Canoe|canoa
Selfie|selfie
Rickshaw|risciò
Barn|fienile
Archery|tiro con l'arco
Aerospace engineering|aerospazio
Storm|tempesta,temporale
Helmet|casco
"""

    /** Categorie: parola cercata -> insieme di etichette */
    private val CATEGORIE: Map<String, Set<String>> by lazy {
        val animali = setOf("Bird", "Shetland sheepdog", "Pomacentridae", "Gerbil", "Bear", "Cattle", "Cat", "Cairn terrier",
            "Dalmatian", "Ragdoll", "Horse", "Pixie-bob", "Herd", "Insect", "Penguin", "Shikoku", "Pet", "Duck", "Turtle",
            "Crocodile", "Dog", "Bull", "Butterfly", "Larva", "Seal", "Sphynx", "Basset hound", "Waterfowl", "Himalayan",
            "Cavalier", "Aquarium")
        val cani = setOf("Dog", "Shetland sheepdog", "Cairn terrier", "Dalmatian", "Shikoku", "Cavalier", "Basset hound")
        val gatti = setOf("Cat", "Himalayan", "Ragdoll", "Pixie-bob", "Sphynx")
        val cibo = setOf("Bento", "Cheeseburger", "Fast food", "Hot dog", "Eating", "Cuisine", "Juice", "Couscous", "Cookie",
            "Pasteles", "Gelato", "Food", "Fruit", "Pho", "Coffee", "Cappuccino", "Bread", "Vegetable", "Cake", "Supper",
            "Lunch", "Meal", "Pie", "Pizza", "Sushi", "Wine", "Alcohol", "Cola", "Icing", "Tableware", "Cutlery")
        val natura = setOf("Sky", "Sunset", "Forest", "Lake", "River", "Mountain", "Beach", "Waterfall", "Canyon", "Glacier",
            "Desert", "Dune", "Field", "Prairie", "Cliff", "Volcano", "Aurora", "Rainbow", "Jungle", "Swamp", "Iceberg",
            "Storm", "Fog", "Flora", "Flower", "Plant", "Garden", "Park", "Rock", "Cave", "Reef", "Moon", "Star")
        val mare = setOf("Beach", "Sand", "Underwater", "Reef", "Surfing", "Surfboard", "Swimwear", "Pier", "Boat", "Sailboat",
            "Shell", "Snorkeling", "Windsurfing", "Speedboat", "Lighthouse", "Scuba diving", "Wetsuit", "Cliff")
        val montagna = setOf("Mountain", "Glacier", "Skiing", "Snowboarding", "Cliff", "Backpacking", "Canyon", "Sledding")
        val neve = setOf("Skiing", "Snowboarding", "Sledding", "Sled", "Glacier", "Icicle", "Ice", "Iceberg")
        val veicoli = setOf("Car", "Bus", "Train", "Motorcycle", "Bicycle", "Van", "Vehicle", "Airplane", "Airliner", "Aircraft",
            "Boat", "Helicopter", "Tractor", "Sailboat", "Speedboat", "Rickshaw")
        val auto = setOf("Car", "Vehicle", "Windshield", "Bumper", "Tire", "Odometer", "Van")
        val feste = setOf("Party", "Christmas", "Marriage", "Bride", "Groom", "Cake", "Balloon", "Carnival", "Fireworks",
            "Graduation", "Prom", "Concert", "Event", "Sparkler", "Hanukkah", "Thanksgiving", "Santa claus", "Icing")
        val matrimonio = setOf("Marriage", "Bride", "Groom", "Veil", "Gown", "Tuxedo")
        val compleanno = setOf("Cake", "Balloon", "Party", "Icing", "Sparkler")
        val documenti = setOf("Paper", "Receipt", "Passport", "Newspaper", "Menu", "Poster", "Whiteboard", "Blackboard", "Presentation")
        val sport = setOf("Sports", "Soccer", "Rugby", "Badminton", "Softball", "Curling", "Gymnastics", "Marathon", "Running",
            "Cycling", "Skiing", "Snowboarding", "Surfing", "Swimming", "Rowing", "Racing", "Race", "Competition", "Stadium",
            "Archery", "Rafting", "Windsurfing", "Waterskiing", "Wakeboarding", "Kayak", "Canoe", "Pitch", "Team", "Jersey")
        val citta = setOf("Skyline", "Skyscraper", "Building", "Road", "Tower", "Asphalt", "Bridge", "Monument", "Cathedral",
            "Church", "Palace", "Neon")
        val casa = setOf("Room", "Bedroom", "Kitchen", "Bathroom", "Couch", "Curtain", "Cabinetry", "Countertop", "Shelf",
            "Drawer", "Cushion", "Pillow", "Lampshade", "Loveseat", "Sink", "Tile", "Chair", "Desk")
        val musica = setOf("Concert", "Musician", "Singer", "Piano", "Musical instrument", "Deejay", "Pop music", "Song", "Nightclub")
        val fiori = setOf("Flower", "Petal", "Flowerpot", "Flora", "Garden")
        val piante = setOf("Plant", "Flora", "Flowerpot", "Garden", "Forest", "Twig", "Branch", "Flower")
        val acqua = setOf("Lake", "River", "Pool", "Waterfall", "Underwater", "Swimming", "Beach", "Reef", "Dam", "Swamp")
        val bambini = setOf("Baby", "Toy", "Playground", "Stuffed toy", "Plush", "Swing", "Lego")
        val aerei = setOf("Airplane", "Airliner", "Aircraft", "Aviation", "Helicopter", "Aerospace engineering", "Wing")
        val barche = setOf("Boat", "Sailboat", "Speedboat", "Skiff", "Brig", "Clipper", "Frigate", "Kayak", "Canoe", "Shipwreck")
        val notte = setOf("Moon", "Star", "Nebula", "Aurora", "Neon", "Fireworks", "Nightclub")
        mapOf(
            "animali" to animali, "animale" to animali,
            "cani" to cani, "cane" to cani, "cagnolino" to cani,
            "gatti" to gatti, "gatto" to gatti, "micio" to gatti, "gattino" to gatti,
            "cibo" to cibo, "mangiare" to cibo, "piatti" to cibo, "piatto" to cibo, "ristorante" to cibo,
            "natura" to natura, "paesaggio" to natura, "paesaggi" to natura, "panorama" to natura, "panorami" to natura,
            "mare" to mare, "spiaggia" to mare, "estate" to mare,
            "montagna" to montagna, "montagne" to montagna, "monti" to montagna,
            "neve" to neve, "sci" to neve,
            "veicoli" to veicoli, "mezzi" to veicoli, "trasporti" to veicoli,
            "auto" to auto, "macchina" to auto, "macchine" to auto, "automobile" to auto,
            "festa" to feste, "feste" to feste, "festeggiamenti" to feste,
            "matrimonio" to matrimonio, "matrimoni" to matrimonio, "nozze" to matrimonio, "sposi" to matrimonio,
            "compleanno" to compleanno, "compleanni" to compleanno,
            "documenti" to documenti, "documento" to documenti,
            "sport" to sport,
            "citta" to citta,
            "casa" to casa, "interni" to casa,
            "musica" to musica,
            "fiori" to fiori, "fiore" to fiori,
            "piante" to piante, "pianta" to piante, "verde" to piante,
            "acqua" to acqua,
            "bambini" to bambini, "bambino" to bambini, "bimbi" to bambini, "bimbo" to bambini, "neonato" to bambini,
            "aerei" to aerei, "aereo" to aerei, "aeroporto" to aerei,
            "barche" to barche, "barca" to barche, "navi" to barche, "nave" to barche,
            "notte" to notte,
        )
    }

    /** etichetta EN -> termini IT */
    val termini: Map<String, List<String>> by lazy {
        ETICHETTE.lines().map { it.trim() }.filter { '|' in it }.associate { r ->
            val (en, it) = r.split('|', limit = 2)
            en to it.split(',').map { t -> Testo.normalizza(t) }.filter { t -> t.isNotEmpty() }
        }
    }

    /** termine IT/EN normalizzato -> etichette */
    private val indice: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        for ((en, its) in termini) {
            m.getOrPut(Testo.normalizza(en)) { mutableSetOf() }.add(en)
            for (t in its) m.getOrPut(t) { mutableSetOf() }.add(en)
        }
        for ((k, v) in CATEGORIE) m.getOrPut(k) { mutableSetOf() }.addAll(v)
        m
    }

    /** Termini composti da più parole, dal più lungo (es. "occhiali da sole") */
    val composti: List<String> by lazy { indice.keys.filter { ' ' in it }.sortedByDescending { it.length } }

    fun etichettePer(termine: String): Set<String>? {
        indice[termine]?.let { return it }
        if (termine.length < 4) return null
        // plurali/singolari: cani->cane, gatti->gatto, montagne->montagna
        val radice = if (termine.last() in "aeiou") termine.dropLast(1) else termine
        val trovate = HashSet<String>()
        for ((k, v) in indice) if (' ' !in k && k.length >= radice.length && k.startsWith(radice) && k.length <= radice.length + 2) trovate += v
        return trovate.ifEmpty { null }
    }

    private val mostrati: Map<String, String> by lazy {
        ETICHETTE.lines().map { it.trim() }.filter { '|' in it }.associate { r ->
            val (en, it) = r.split('|', limit = 2)
            en to it.split(',').first().trim()
        }
    }

    fun italiano(en: String): String = mostrati[en] ?: en.lowercase()
}
