alter table customer_address set ("unique_constraints" = "ca_address_sk");
alter table customer_demographics set ("unique_constraints" = "cd_demo_sk");
alter table date_dim set ("unique_constraints" = "d_date_sk");
alter table warehouse set ("unique_constraints" = "w_warehouse_sk");
alter table ship_mode set ("unique_constraints" = "sm_ship_mode_sk");
alter table time_dim set ("unique_constraints" = "t_time_sk");
alter table reason set ("unique_constraints" = "r_reason_sk");
alter table income_band set ("unique_constraints" = "ib_income_band_sk");
alter table item set ("unique_constraints" = "i_item_sk");
alter table store set ("unique_constraints" = "s_store_sk");
alter table call_center set ("unique_constraints" = "cc_call_center_sk");
alter table customer set ("unique_constraints" = "c_customer_sk;c_customer_id");
alter table web_site set ("unique_constraints" = "web_site_sk");
alter table store_returns set ("unique_constraints" = "sr_item_sk, sr_ticket_number");
alter table household_demographics set ("unique_constraints" = "hd_demo_sk");
alter table web_page set ("unique_constraints" = "wp_web_page_sk");
alter table promotion set ("unique_constraints" = "p_promo_sk");
alter table catalog_page set ("unique_constraints" = "cp_catalog_page_sk");
alter table inventory set ("unique_constraints" = "inv_date_sk, inv_item_sk, inv_warehouse_sk");
alter table catalog_returns set ("unique_constraints" = "cr_item_sk, cr_order_number");
alter table web_returns set ("unique_constraints" = "wr_item_sk, wr_order_number");
alter table web_sales set ("unique_constraints" = "ws_item_sk, ws_order_number");
alter table catalog_sales set ("unique_constraints" = "cs_item_sk, cs_order_number");
alter table store_sales set ("unique_constraints" = "ss_item_sk, ss_ticket_number");
