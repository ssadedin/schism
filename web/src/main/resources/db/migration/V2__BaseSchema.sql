CREATE TABLE auth_user (
    id bigint NOT NULL,
    version bigint NOT NULL,
    password_expired boolean NOT NULL,
    username character varying(255) NOT NULL,
    account_locked boolean NOT NULL,
    password character varying(255) NOT NULL,
    account_expired boolean NOT NULL,
    enabled boolean NOT NULL
);


--
-- TOC entry 187 (class 1259 OID 51423)
-- Name: breakpoint; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE breakpoint (
    id bigint NOT NULL,
    version bigint NOT NULL,
    annotations text,
    sample_count integer NOT NULL,
    obs integer NOT NULL,
    chr character varying(255) NOT NULL,
    pos integer NOT NULL
);


--
-- TOC entry 188 (class 1259 OID 51431)
-- Name: breakpoint_observation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE breakpoint_observation (
    id bigint NOT NULL,
    version bigint NOT NULL,
    sample_id bigint NOT NULL,
    start_clips integer NOT NULL,
    total integer NOT NULL,
    partner_id bigint,
    bases character varying(255) NOT NULL,
    breakpoint_id bigint NOT NULL,
    end_clips integer NOT NULL
);


--
-- TOC entry 189 (class 1259 OID 51436)
-- Name: cohort; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE cohort (
    id bigint NOT NULL,
    version bigint NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(255) NOT NULL
);


--
-- TOC entry 190 (class 1259 OID 51444)
-- Name: cohort_auth_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE cohort_auth_user (
    cohort_users_id bigint NOT NULL,
    user_id bigint
);


--
-- TOC entry 191 (class 1259 OID 51447)
-- Name: cohort_samples; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE cohort_samples (
    sample_id bigint NOT NULL,
    cohort_id bigint NOT NULL
);


--
-- TOC entry 192 (class 1259 OID 51452)
-- Name: family; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE family (
    id bigint NOT NULL,
    version bigint NOT NULL,
    name character varying(255) NOT NULL
);


--
-- TOC entry 193 (class 1259 OID 51457)
-- Name: gene_transcript; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE gene_transcript (
    id bigint NOT NULL,
    version bigint NOT NULL,
    tx_start integer NOT NULL,
    cds_start integer NOT NULL,
    strand character varying(255) NOT NULL,
    num_exons integer NOT NULL,
    details text NOT NULL,
    chr character varying(255) NOT NULL,
    tx_end integer NOT NULL,
    transcript character varying(255) NOT NULL,
    cds_end integer NOT NULL
);


--
-- TOC entry 185 (class 1259 OID 51413)
-- Name: hibernate_sequence; Type: SEQUENCE; Schema: public; Owner: -
--

-- CREATE SEQUENCE hibernate_sequence
--     START WITH 1
--     INCREMENT BY 1
--     NO MINVALUE
--     NO MAXVALUE
--     CACHE 1;


--
-- TOC entry 194 (class 1259 OID 51465)
-- Name: history_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE history_event (
    id bigint NOT NULL,
    version bigint NOT NULL,
    date_created timestamp with time zone NOT NULL,
    cohort_id bigint NOT NULL,
    user_id bigint NOT NULL,
    description character varying(255) NOT NULL
);


--
-- TOC entry 195 (class 1259 OID 51470)
-- Name: load_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE load_operation (
    id bigint NOT NULL,
    version bigint NOT NULL,
    file character varying(255) NOT NULL,
    processed integer NOT NULL,
    total integer NOT NULL,
    phase integer NOT NULL,
    cohort_id bigint NOT NULL,
    pedigree_file character varying(255),
    breakpoint_count integer NOT NULL,
    state character varying(255) NOT NULL
);


--
-- TOC entry 196 (class 1259 OID 51478)
-- Name: phenotype; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE phenotype (
    id bigint NOT NULL,
    version bigint NOT NULL,
    name character varying(255) NOT NULL
);


--
-- TOC entry 197 (class 1259 OID 51483)
-- Name: role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE role (
    id bigint NOT NULL,
    version bigint NOT NULL,
    authority character varying(255) NOT NULL
);


--
-- TOC entry 198 (class 1259 OID 51488)
-- Name: sample; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE sample (
    id bigint NOT NULL,
    version bigint NOT NULL,
    sample_id character varying(255) NOT NULL,
    family_id bigint NOT NULL,
    sex character varying(255) NOT NULL,
    father_id bigint,
    mother_id bigint
);


--
-- TOC entry 199 (class 1259 OID 51496)
-- Name: sample_phenotype; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE sample_phenotype (
    sample_phenotypes_id bigint NOT NULL,
    phenotype_id bigint,
    phenotypes_idx integer
);


--
-- TOC entry 200 (class 1259 OID 51499)
-- Name: user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE user_role (
    user_id bigint NOT NULL,
    role_id bigint NOT NULL
);


--
-- TOC entry 2493 (class 0 OID 51415)
-- Dependencies: 186
-- Data for Name: auth_user; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO auth_user VALUES (3, 0, false, 'admin', false, '$2a$10$Qr4Y3R4bOjRayGuSVkKwSOA/mNzNxmCxaM6Eb06.dAz7thXWxiQHO', false, true);
INSERT INTO auth_user VALUES (4, 0, false, 'testuser', false, '$2a$10$KSac0WG30xJle0xhEw1RiO5MzivAgiQ/VaK4TW9x8PM6SDAx0i65G', false, true);


--
-- TOC entry 2494 (class 0 OID 51423)
-- Dependencies: 187
-- Data for Name: breakpoint; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO breakpoint VALUES (1000020000, 1, '{"genes": [{"symbol": "DVL1", "cdsDistance": 34}, {"symbol": "DMD", "cdsDistance": 460}]}', 2, 33, 'chr1', 20000);
INSERT INTO breakpoint VALUES (2038615727, 0, '{"genes": [{"symbol": "SCN5A", "cdsDistance": 0}]}', 1, 20, 'chr3', 38615727);


--
-- TOC entry 2495 (class 0 OID 51431)
-- Dependencies: 188
-- Data for Name: breakpoint_observation; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO breakpoint_observation VALUES (11, 0, 9, 5, 10, NULL, 'CATAGTAC', 1000020000, 5);
INSERT INTO breakpoint_observation VALUES (12, 0, 10, 12, 23, NULL, 'AATAATAC', 1000020000, 21);
INSERT INTO breakpoint_observation VALUES (13, 0, 9, 5, 10, NULL, 'GGGCCAAT', 2038615727, 5);

--
-- TOC entry 2496 (class 0 OID 51436)
-- Dependencies: 189
-- Data for Name: cohort; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO cohort VALUES (5, 2, 'Test Cohort', 'A test cohort for developing and debugging Schism-Web');


--
-- TOC entry 2497 (class 0 OID 51444)
-- Dependencies: 190
-- Data for Name: cohort_auth_user; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO cohort_auth_user VALUES (5, 3);
INSERT INTO cohort_auth_user VALUES (5, 4);


--
-- TOC entry 2498 (class 0 OID 51447)
-- Dependencies: 191
-- Data for Name: cohort_samples; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO cohort_samples VALUES (7, 5);
INSERT INTO cohort_samples VALUES (8, 5);
INSERT INTO cohort_samples VALUES (9, 5);
INSERT INTO cohort_samples VALUES (10, 5);

--
-- TOC entry 2499 (class 0 OID 51452)
-- Dependencies: 192
-- Data for Name: family; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO family VALUES (6, 0, 'Test Family');


