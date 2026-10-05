package com.generals.api.dto;

/**
 * Something a player said to the other one.
 *
 * <p>{@code author} is filled in by the server from the seat, never taken from the request:
 * a client that sent someone else's name would be naming people who never spoke.
 */
public record ChatMessageDto(long id, String color, String author, String text, String at) {
}